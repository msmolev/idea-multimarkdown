/*
 * Copyright (c) 2015-2015 Vladimir Schneider <vladimir.schneider@gmail.com>, all rights reserved.
 *
 * This code is private property of the copyright holder and cannot be used without
 * having obtained a license or prior written permission of the of the copyright holder.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package com.vladsch.idea.multimarkdown.util

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.vladsch.idea.multimarkdown.MultiMarkdownPlugin
import org.apache.log4j.Logger
import java.util.*
import kotlin.text.RegexOption

class GitHubLinkResolver(projectResolver: LinkResolver.ProjectResolver, containingFile: FileRef, branchOrTag: String? = null) : LinkResolver(projectResolver, containingFile, branchOrTag) {
    private val logger = Logger.getLogger(GitHubLinkResolver::class.java)

    companion object {
        @JvmStatic @JvmField val GITHUB_BLOB_NAME = "blob"
        @JvmStatic @JvmField val GITHUB_FORK_NAME = "fork"
        @JvmStatic @JvmField val GITHUB_GRAPHS_NAME = "graphs"
        @JvmStatic @JvmField val GITHUB_ISSUES_NAME = "issues"
        @JvmStatic @JvmField val GITHUB_PULLS_NAME = "pulls"
        @JvmStatic @JvmField val GITHUB_PULSE_NAME = "pulse"
        @JvmStatic @JvmField val GITHUB_RAW_NAME = "raw"
        @JvmStatic @JvmField val GITHUB_WIKI_NAME = "wiki"

        // IMPORTANT: keep alphabetically sorted. These are not re-sorted after match
        @JvmStatic @JvmField val GITHUB_LINKS = arrayOf(
                GITHUB_BLOB_NAME,
                GITHUB_FORK_NAME,
                GITHUB_GRAPHS_NAME,
                GITHUB_ISSUES_NAME,
                GITHUB_PULLS_NAME,
                GITHUB_PULSE_NAME,
                GITHUB_RAW_NAME,
                GITHUB_WIKI_NAME
        )

        @JvmStatic @JvmField val GITHUB_NON_FILE_LINKS = arrayOf(
                GITHUB_FORK_NAME,
                GITHUB_GRAPHS_NAME,
                GITHUB_ISSUES_NAME,
                GITHUB_PULLS_NAME,
                GITHUB_PULSE_NAME
        )

        @JvmStatic @JvmField val GITHUB_TARGET_LINKS = arrayOf(
                GITHUB_FORK_NAME,
                GITHUB_GRAPHS_NAME,
                GITHUB_ISSUES_NAME,
                GITHUB_PULLS_NAME,
                GITHUB_PULSE_NAME,
                GITHUB_WIKI_NAME
        )
    }

    private var matcher: GitHubLinkMatcher? = null

    fun getMatcher(linkRef: LinkRef, linkRefWasURI: Boolean): GitHubLinkMatcher {
        var _matcher = matcher
        val normLinkRef = normalizedLinkRef(linkRef)
        if (_matcher === null || _matcher.linkRef != normLinkRef) {
            _matcher = GitHubLinkMatcher(projectResolver, normLinkRef, linkRefWasURI)
            matcher = _matcher
        }
        return _matcher
    }

    fun getLastMatcher(): GitHubLinkMatcher? {
        return matcher
    }

    constructor(virtualFile: VirtualFile, project: Project) : this(MultiMarkdownPlugin.getProjectComponent(project)!!, FileRef(virtualFile.path))

    constructor(projectFileRef: ProjectFileRef) : this(MultiMarkdownPlugin.getProjectComponent(projectFileRef.project)!!, projectFileRef)

    constructor(psiFile: PsiFile) : this(MultiMarkdownPlugin.getProjectComponent(psiFile.project)!!, FileRef(psiFile.virtualFile.path))

    constructor(psiElement: PsiElement) : this(psiElement.containingFile)

    internal val linkInspector: GitHubLinkInspector by lazy { GitHubLinkInspector(this) }

    // TEST: this needs tests to make sure it works
    override fun isResolvedTo(linkRef: LinkRef, targetRef: FileRef, withExtForWikiPage: Boolean?, branchOrTag: String?): Boolean {
        assertContainingFile(linkRef)
        val normLinkRef = normalizedLinkRef(linkRef)
        val linkRefText = linkAddress(normLinkRef, targetRef, withExtForWikiPage, branchOrTag, "")
        return linkRef.filePath.equals(linkRefText, ignoreCase = targetRef.isWikiPage && !linkRef.hasExt)
    }

    override fun isResolved(linkRef: LinkRef, options: Int, inList: List<PathInfo>?): Boolean {
        assertContainingFile(linkRef)
        val normLinkRef = normalizedLinkRef(linkRef)
        return resolve(normLinkRef, options, inList) != null
    }

    override fun resolve(linkRef: LinkRef, options: Int, inList: List<PathInfo>?): PathInfo? {
        assertContainingFile(linkRef)
        // TODO: if only want local, then can try to resolve external links to local file refs if they map, for that need to parse the
        // LinkRef's URL file path and remove the repoPrefix for non-Wiki and wikiRepoPrefix for wiki files, then prefix the result with the corresponding basePath
        var linkRef_ = normalizedLinkRef(linkRef)
        var targetRef: PathInfo = linkRef_
        var opts = options

        if (linkRef_.isSelfAnchor) {
            if (linkRef_ is WikiLinkRef && linkRef_.filePath.isEmpty()) {
                // here it is a pure anchor wiki link, which does not resolve
                if (!wantLooseMatch(options)) return null
            }

            targetRef = linkRef_.containingFile
            linkRef_ = linkRef_.replaceFilePath(if (linkRef_.hasExt || !linkRef_.containingFile.isUnderWikiDir) targetRef.fileName else targetRef.fileNameNoExt)
        }

        if (targetRef.isURI) {
            val relPath = uriToRelativeLink(linkRef_)
            if (relPath is LinkRef) {
                linkRef_ = relPath
                opts = opts or LINK_REF_WAS_URI
            }
        }

        if (!linkRef_.isAbsolute) {
            // resolve the relative link as per requested options
            val linkRefMatcher = getMatcher(linkRef_, linkRefWasURI(opts))
            val matches = getMatchedRefs(linkRef_, linkRefMatcher, opts, inList)
            var resolvedRef = (if (matches.size > 0) matches[0] else null) ?: return null
            targetRef = resolvedRef
            return targetRef
        }

        return processMatchOptions(linkRef_, targetRef, opts)
    }

    fun isExternalUnchecked(linkRef: LinkRef):Boolean {
        val vcsRoot = projectResolver.getVcsRoot(linkRef.containingFile)
        return linkRef.isExternal && (vcsRoot == null || !linkRef.filePath.toLowerCase().startsWith(vcsRoot.baseUrl.toLowerCase()))
    }

    override fun multiResolve(linkRef: LinkRef, options: Int, inList: List<PathInfo>?): List<PathInfo> {
        assertContainingFile(linkRef)
        var relLink = normalizedLinkRef(linkRef)
        var opts = options
        if (relLink.isURI) {
            val relPath = uriToRelativeLink(relLink)
            if (relPath is LinkRef) {
                relLink = relPath
                opts = opts or LINK_REF_WAS_URI
            }
        }

        val linkRefMatcher = getMatcher(relLink, linkRefWasURI(opts))
        return getMatchedRefs(relLink, linkRefMatcher, opts, inList)
    }

    // TODO: change this to take an exact resolve list and a loose matched list so that
    // all types of issues could be analyzed, not just based on single target
    override fun inspect(linkRef: LinkRef, targetRef: FileRef, referenceId: Any?): List<InspectionResult> {
        assertContainingFile(linkRef)
        val normLinkRef = normalizedLinkRef(linkRef)
        return linkInspector.inspect(normLinkRef, targetRef, referenceId)
    }

    protected fun getTargetFileTypes(extensions: List<String>?): HashSet<FileType> {
        val typeSet = HashSet<FileType>()
        if (extensions == null || project == null) return typeSet

        val typeManager = FileTypeManager.getInstance() as FileTypeManagerImpl
        for (ext in extensions) {
            val targetFileType = typeManager.getFileTypeByExtension(ext.removePrefix("."))
            typeSet.add(targetFileType)
        }
        return typeSet
    }

    fun processMatchOptions(linkRef: LinkRef, targetRef: PathInfo, options: Int): PathInfo? {
        assert(linkRef === normalizedLinkRef(linkRef))

        val fileRefAsURI = { LinkRef(containingFile, "file://" + LinkRef.urlEncode(targetRef.filePath), null, targetRef as FileRef?) }
        val uriAsFileRef = {
            assert(targetRef.filePath.startsWith("file:"), { "Expected local targetRef, got $targetRef" })
            if (project != null) {
                targetRef.projectFileRef(project)
            } else {
                // preserve rawFile status
                val fileRef = FileRef(targetRef.filePath.removePrefix("file:").removePrefix("//").prefixWith('/'))
                if (targetRef is LinkRef && targetRef.targetRef != null && targetRef.targetRef.isRawFile) fileRef.isRawFile = true
                fileRef
            }
        }

        if (targetRef is FileRef) {
            val vcsRoot = projectResolver.getVcsRoot(targetRef)
            val isUnderVcs: Boolean = vcsRoot.ifNotNull { projectResolver.isUnderVcs(targetRef) } ?: false
            val fileRefAsURL = {
                val remoteUrl = vcsRoot?.urlForVcsRemote(targetRef, targetRef.isRawFile || wikiLinkHasRealExt(linkRef, targetRef), linkRef.anchor, branchOrTag, if (linkRef is ImageLinkRef || targetRef.isRawFile) "raw" else null)
                if (remoteUrl != null) {
                    // putting an if () in to select parameter crashes the compiler:
                    //                    val urlRef = LinkRef.parseLinkRef(linkRef.containingFile, remoteUrl, targetRef, if (linkRef is ImageLinkRef) ::ImageLinkRef else ::LinkRef)
                    val urlRef =
                            if (linkRef is ImageLinkRef) LinkRef.parseLinkRef(linkRef.containingFile, remoteUrl, targetRef, ::ImageLinkRef)
                            else LinkRef.parseLinkRef(linkRef.containingFile, remoteUrl, targetRef, ::LinkRef)
                    assert(urlRef.isExternal, { "expected to get URL, instead got $urlRef" })
                    urlRef
                } else {
                    null
                }
            }

            if (vcsRoot != null && isUnderVcs) {
                // it is a remote reference
                val remoteType = wantRemoteType(options)
                when (remoteType) {
                    Remote.NONE -> return null
                    Remote.REF -> return targetRef
                    Remote.URI -> return fileRefAsURI()
                    else -> {
                        assert(remoteType == Remote.URL, { "Not all RemoteTypes are handled, expected Remote.URL got ${remoteType.testData()}" })
                        return fileRefAsURL()
                    }
                }
            } else {
                // local file
                val localType = wantLocalType(options)
                when (localType) {
                    Local.NONE -> return null
                    Local.REF -> return targetRef
                    Local.URI -> return fileRefAsURI()
                    else -> {
                        assert(localType == Local.URL, { "Not all LocalTypes are handled, expected Local.URL got ${localType.testData()}" })
                        return fileRefAsURL()
                    }
                }
            }
        }
        return null
    }

    fun getMatchedRefs(linkRef: LinkRef, linkMatcher: GitHubLinkMatcher, options: Int, fromList: List<PathInfo>?): List<PathInfo> {
        assert(linkRef === normalizedLinkRef(linkRef))

        // process the files that match the pattern and put them in the list
        var matches = ArrayList<PathInfo>()

        if (linkMatcher.isOnlyLooseMatchValid && !wantLooseMatch(options)) return matches

        if (!linkMatcher.gitHubLinks) {
            val linkLooseMatch = linkMatcher.linkLooseMatch
            val linkAllMatch = linkMatcher.linkAllMatch
            if (linkLooseMatch == null || linkAllMatch == null) return matches

            // TODO: need to have a flag or to modify the regex to exclude wiki matches when exact matching in the repo
            val allMatchWiki =
                    if (wantLooseMatch(options)) {
                        linkLooseMatch.toRegex(RegexOption.IGNORE_CASE)
                    } else {
                        if (linkMatcher.wikiMatchingRules) {
                            linkAllMatch.toRegex(RegexOption.IGNORE_CASE)
                        } else {
                            linkMatcher.linkFileMatch!!.toRegex()
                        }
                    }

            val allMatchNonWiki =
                    if (wantLooseMatch(options)) {
                        allMatchWiki
                    } else {
                        if (linkMatcher.wikiMatchingRules) {
                            linkAllMatch.toRegex()
                        } else {
                            allMatchWiki
                        }
                    }

            val allExtensions =
                    if (wantLooseMatch(options)) {
                        linkMatcher.linkLooseMatchExtensions
                    } else {
                        linkMatcher.linkAllMatchExtensions
                    }

            //            val allFiles = ArrayList<String>()

            // TEST: raw links will produce raw relative and remote url addresses
            val rawGitHubLink = linkMatcher.gitHubLink == "raw"

            if (fromList == null) {
                val targetFileTypes = getTargetFileTypes(allExtensions)
                if (targetFileTypes.isEmpty() || project == null) {
                    // Only used in testing, runtime uses FileBasedIndex
                    if (project != null) return ArrayList(0)

                    val projectFileList = projectResolver.projectFileList(allExtensions)
                    if (projectFileList != null) {
                        for (fileRef in projectFileList) {
                            if (fileRef.filePath.matches(if (fileRef.isWikiPage) allMatchWiki else allMatchNonWiki)) {
                                // here we need to test for wiki page links that resolve to raw files, these have to match case sensitive
                                if (allMatchNonWiki === allMatchWiki || !linkMatcher.wikiMatchingRules || !linkRef.hasExt || fileRef.filePath.matches(allMatchNonWiki)) {
                                    val newFileRef = if (rawGitHubLink) FileRef(fileRef) else fileRef
                                    if (rawGitHubLink) newFileRef.isRawFile = true
                                    matches.add(newFileRef)
                                }
                            }
                        }
                    }
                } else {
                    //val projectFileList = projectResolver.projectFileList(targetFileTypes)
                    val instance = FileBasedIndex.getInstance() as FileBasedIndexImpl
                    for (type in targetFileTypes) {
                        val containingFiles = instance.processFilesContainingAllKeys(FileTypeIndex.NAME, setOf(type), GlobalSearchScope.projectScope(project), null, Processor<VirtualFile> { virtualFile ->
                            //                            allFiles.add(virtualFile.path)
                            if (virtualFile.path.matches(allMatchWiki)) {
                                val fileRef = ProjectFileRef(virtualFile, project)
                                if (allMatchNonWiki === allMatchWiki || !linkMatcher.wikiMatchingRules || !linkRef.hasExt || fileRef.filePath.matches(allMatchNonWiki)) {
                                    val newFileRef = if (rawGitHubLink) FileRef(fileRef) else fileRef
                                    if (rawGitHubLink) newFileRef.isRawFile = true
                                    matches.add(newFileRef)
                                }
                            }
                            true
                        })
                    }
                }
            } else {
                // filter from a pre-created list, used to get looseMatch, then from that list get exact matches
                for (fileRef in fromList) {
                    // here we can have both fileRefs and linkRefs, we only handle fileRefs, linkRefs are silently dropped
                    if (fileRef is FileRef) {
                        if (fileRef.filePath.matches(if (fileRef.isWikiPage) allMatchWiki else allMatchNonWiki)) {
                            // here we need to test for wiki page links that resolve to raw files, these have to match case sensitive
                            if (allMatchNonWiki === allMatchWiki || !linkMatcher.wikiMatchingRules || !fileRef.isWikiPage || !linkRef.hasExt || fileRef.filePath.matches(allMatchNonWiki)) {
                                // if isRawFile is set we cannot reuse it, since in our case it may no longer be raw access
                                if (fileRef.isRawFile == rawGitHubLink) matches.add(fileRef)
                                else if (!rawGitHubLink) matches.add(FileRef(fileRef))
                                else {
                                    val newFileRef = FileRef(fileRef)
                                    newFileRef.isRawFile = true
                                    matches.add(newFileRef)
                                }
                            }
                        }
                    }
                }
            }

            val rawWikiFileRefMatches = HashSet<FileRef>()

            // now we need to weed out the matches that will not work, unless this is a loose match
            if (linkMatcher.wikiMatchingRules) {
                // here some will be case sensitive some not,
                // anchor and ext based matches are to wiki pages
                if (linkRef is WikiLinkRef) {
                    // these match wiki pages, also have priority over the non-pages, ie. if Test.kt and Test.kt.md exists, then the .md will be matched first
                    // not case sensitive: linkSubExtMatch = "^$fixedPrefix$subDirPattern$filenamePattern$extensionPattern$"
                    // not case sensitive: linkSubAnchorExtMatch = "^$fixedPrefix$subDirPattern$filenamePattern$anchorPattern$extensionPattern$"
                    //val pageMatch = (linkMatcher.linkSubExtMatch + "|" + linkMatcher.linkSubAnchorExtMatch).toRegex(RegexOption.IGNORE_CASE)

                    // these match raw file content
                    // case sensitive: linkFileMatch = "^$fixedPrefix$filenamePattern$"
                    // case sensitive: linkFileAnchorMatch = "^$fixedPrefix$filenamePattern$anchorPattern$"
                    val fileOrAnchorMatch = (if (linkMatcher.linkFileAnchorMatch == null) linkMatcher.linkFileMatch else linkMatcher.linkFileMatch + "|" + linkMatcher.linkFileAnchorMatch)?.toRegex()
                    if (fileOrAnchorMatch != null) {
                        for (fileRef in matches) {
                            if (fileRef is FileRef && fileRef.filePath.matches(fileOrAnchorMatch)) {
                                rawWikiFileRefMatches.add(fileRef)
                                if (fileRef.isUnderWikiDir) fileRef.isRawFile = true
                            }
                        }
                    }

                } else {
                    // these match wiki pages, also have priority over the non-pages, ie. if Test.kt and Test.kt.md exists, then the .md will be matched first
                    // not case sensitive: linkSubExtMatch = "^$fixedPrefix$subDirPattern$filenamePattern$extensionPattern$"

                    // these match raw file content and images
                    // case sensitive: linkFileMatch = "^$fixedPrefix$filenamePattern$"
                    val fileMatch = linkMatcher.linkFileMatch?.toRegex()
                    if (fileMatch != null) {
                        for (fileRef in matches) {
                            if (fileRef is FileRef) {
                                // it will be raw access if it is under the wiki directory  and has a 'real' extension
                                if (!fileRef.isWikiPageExt || fileRef.filePath.matches(fileMatch)) {
                                    rawWikiFileRefMatches.add(fileRef)
                                    if (fileRef.isUnderWikiDir) fileRef.isRawFile = true
                                }
                            }
                        }
                    }
                }
            } else {
                // case sensitive: linkFileMatch = "^$fixedPrefix$filenamePattern$"
                // these are already set for raw if taken from the raw/ access URL and all are exact matches
                for (fileRef in matches) {
                    if (fileRef is FileRef) {
                        rawWikiFileRefMatches.add(fileRef)
                        if (fileRef.isUnderWikiDir) fileRef.isRawFile = true
                    }
                }
            }

            if (linkRef is ImageLinkRef) {
                // have to remove all that will not resolve, unless loose matching
                var resolved = if (linkRefWasURI(options) || !linkRef.containingFile.isWikiPage) matches else ArrayList<PathInfo>()
                var unresolved = ArrayList<PathInfo>()
                if (!linkRefWasURI(options) && linkRef.containingFile.isWikiPage) {
                    matches.forEach {
                        // if it is an image it should only resolve for raw
                        if (it is FileRef) {
                            val resolvedLinkAddress = linkAddress(linkRef, it, null, null, null)
                            if (linkRef.filePath.equals(resolvedLinkAddress, ignoreCase = true)) resolved.add(it)
                            else unresolved.add(it)
                        } else {
                            unresolved.add(it)
                        }
                    }
                }

                var linkFileMatchRegex = linkMatcher.linkFileMatch?.toRegex() ?: linkAllMatch.toRegex()
                resolved.sort { self, other ->
                    if (self.filePath.matches(linkFileMatchRegex) && !other.filePath.matches(linkFileMatchRegex)) -1
                    else if (!self.filePath.matches(linkFileMatchRegex) && other.filePath.matches(linkFileMatchRegex)) 1
                    else self.compareTo(other)
                }

                if (wantLooseMatch(options)) {
                    unresolved.sort { self, other ->
                        if (self.filePath.matches(linkFileMatchRegex) && !other.filePath.matches(linkFileMatchRegex)) -1
                        else if (!self.filePath.matches(linkFileMatchRegex) && other.filePath.matches(linkFileMatchRegex)) 1
                        else self.compareTo(other)
                    }
                    matches = resolved
                    matches.addAll(unresolved)
                } else {
                    matches = resolved
                }
            } else {
                if (matches.size > 1) matches.sort { self, other ->
                    if (self is FileRef && other is FileRef) {
                        if (self in rawWikiFileRefMatches && other !in rawWikiFileRefMatches) 1
                        else if (self !in rawWikiFileRefMatches && other in rawWikiFileRefMatches) -1
                        else if (self in rawWikiFileRefMatches && other in rawWikiFileRefMatches) {
                            if (self.isWikiPageExt && !other.isWikiPageExt) -1
                            else if (!self.isWikiPageExt && other.isWikiPageExt) 1
                            else self.compareTo(other)
                        } else self.compareTo(other)
                    } else self.compareTo(other)
                }
            }

            // here we post process for other than just vanilla fileRef result type
            if (!wantLocalREF(options) || !wantRemoteREF(options)) {
                val postProcessedMatches = ArrayList<PathInfo>()

                for (fileRef in matches) {
                    val pathInfo = processMatchOptions(linkRef, fileRef, options)
                    if (pathInfo != null) postProcessedMatches.add(pathInfo)
                }

                matches = postProcessedMatches
            }
        }

        if (wantLinks(options) && linkRef !is WikiLinkRef && linkRef !is ImageLinkRef) {
            if (linkMatcher.gitHubLinks) {
                // no need to check for links, the matcher has the link already set and we even pass all the stuff after the link
                val remoteUrl = projectResolver.getVcsRoot(linkRef.containingFile)?.baseUrl
                if (remoteUrl != null) {
                    assert(remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://"), { "remote vcsRepoBase has to start with http:// or https://, instead got $remoteUrl" })
                    val urlRef = LinkRef.parseLinkRef(linkRef.containingFile, remoteUrl.suffixWith('/') + linkMatcher.gitHubLinkWithParams, null)
                    matches.add(urlRef)
                }
            } else {
                val linkLooseMatch = linkMatcher.linkLooseMatch
                if (!linkMatcher.isOnlyLooseMatchValid && linkMatcher.effectiveExt.isNullOrEmpty() && linkLooseMatch != null) {
                    val vcsRoot = projectResolver.getVcsRoot(linkRef.containingFile)
                    if (vcsRoot != null) {
                        val remoteUrl = vcsRoot.baseUrl
                        val basePath = vcsRoot.basePath
                        val allMatchWiki = linkLooseMatch.toRegex(RegexOption.IGNORE_CASE)

                        assert(remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://"), { "remote vcsRepoBase has to start with http:// or https://, instead got $remoteUrl" })

                        for (link in GITHUB_NON_FILE_LINKS) {
                            if ((basePath.suffixWith('/') + link).matches(allMatchWiki)) {
                                val urlRef = LinkRef.parseLinkRef(linkRef.containingFile, remoteUrl.suffixWith('/') + link, null)
                                matches.add(urlRef)
                            }
                        }
                    }
                }
            }
        }

        return matches
    }

    fun logicalRemotePath(fileRef: FileRef, useWikiPageActualLocation: Boolean, isSourceRef: Boolean, isImageLinkRef: Boolean, branchOrTag: String?): PathInfo {
        var filePathInfo: PathInfo

        if (fileRef.isUnderWikiDir) {
            if (useWikiPageActualLocation && !isSourceRef) filePathInfo = PathInfo(fileRef.path)
            else if (fileRef.isWikiHomePage && isSourceRef && isImageLinkRef) filePathInfo = PathInfo.appendParts(fullPath = fileRef.wikiDir, parts = "..")
            else filePathInfo = PathInfo(fileRef.wikiDir)
        } else {
            val gitHubVcsRoot = projectResolver.getVcsRoot(fileRef)
            val vcsMainRepoBase = (gitHubVcsRoot?.mainRepoBaseDir ?: projectBasePath).suffixWith('/')

            filePathInfo = PathInfo(vcsMainRepoBase + (if (isImageLinkRef || fileRef.isRawFile) "raw/" else "blob/") + (branchOrTag ?: "master").suffixWith('/') + PathInfo.relativePath(vcsMainRepoBase, fileRef.path, withPrefix = false))
        }
        return filePathInfo
    }

    override fun relativePath(linkRef: LinkRef, targetRef: FileRef, withExtForWikiPage: Boolean, branchOrTag: String?): String {
        assertContainingFile(linkRef)
        assert(linkRef === normalizedLinkRef(linkRef))

        val containingFilePath = logicalRemotePath(containingFile, useWikiPageActualLocation = false, isSourceRef = true, isImageLinkRef = linkRef is ImageLinkRef, branchOrTag = branchOrTag).filePath.suffixWith('/')
        val targetFilePath = logicalRemotePath(targetRef, useWikiPageActualLocation = withExtForWikiPage, isSourceRef = false, isImageLinkRef = linkRef is ImageLinkRef, branchOrTag = branchOrTag).filePath.suffixWith('/')

        //        val linkBranchOrTag = branchOrTag ?: extractBranchOrTag(linkRef)
        return PathInfo.relativePath(containingFilePath, targetFilePath, withPrefix = true)
    }

    fun linkAddress(targetRef: PathInfo, withExtForWikiPage: Boolean? = null, branchOrTag: String?, anchor: String?): String {
        val linkRef = LinkRef(containingFile, targetRef.fileNameNoExt, anchor, null);
        return linkAddress(linkRef, targetRef, withExtForWikiPage, branchOrTag, anchor)
    }

    fun wikiLinkAddress(targetRef: PathInfo, withExtForWikiPage: Boolean? = null, branchOrTag: String?, anchor: String?): String {
        val linkRef = WikiLinkRef(containingFile, targetRef.fileNameNoExt, anchor, null);
        return linkAddress(linkRef, targetRef, withExtForWikiPage, branchOrTag, anchor)
    }

    fun imageLinkAddress(targetRef: PathInfo, withExtForWikiPage: Boolean? = null, branchOrTag: String?, anchor: String?): String {
        val linkRef = ImageLinkRef(containingFile, targetRef.fileNameNoExt, anchor, null);
        return linkAddress(linkRef, targetRef, withExtForWikiPage, branchOrTag, anchor)
    }

    fun linkRef(targetRef: PathInfo, withExtForWikiPage: Boolean? = null, branchOrTag: String?, anchor: String?): LinkRef {
        return LinkRef.parseLinkRef(containingFile, linkAddress(targetRef, withExtForWikiPage, branchOrTag, anchor), targetRef as? FileRef, ::LinkRef)
    }

    fun wikiLinkRef(targetRef: PathInfo, withExtForWikiPage: Boolean? = null, branchOrTag: String?, anchor: String?): WikiLinkRef {
        return LinkRef.parseLinkRef(containingFile, wikiLinkAddress(targetRef, withExtForWikiPage, branchOrTag, anchor), targetRef as? FileRef, ::WikiLinkRef) as WikiLinkRef
    }

    fun imageLinkRef(targetRef: PathInfo, withExtForWikiPage: Boolean? = null, branchOrTag: String?, anchor: String?): ImageLinkRef {
        return LinkRef.parseLinkRef(containingFile, imageLinkAddress(targetRef, withExtForWikiPage, branchOrTag, anchor), targetRef as? FileRef, ::ImageLinkRef) as ImageLinkRef
    }

    fun linkRef(linkRef: LinkRef, targetRef: PathInfo, withExtForWikiPage: Boolean?, branchOrTag: String?, anchor: String?): LinkRef {
        assert(linkRef === normalizedLinkRef(linkRef))

        return when (linkRef) {
            is WikiLinkRef -> LinkRef.parseLinkRef(containingFile, linkAddress(linkRef, targetRef, withExtForWikiPage, branchOrTag, anchor), targetRef as? FileRef, ::WikiLinkRef)
            is ImageLinkRef -> LinkRef.parseLinkRef(containingFile, linkAddress(linkRef, targetRef, withExtForWikiPage, branchOrTag, anchor), targetRef as? FileRef, ::ImageLinkRef)
            else -> LinkRef.parseLinkRef(containingFile, linkAddress(linkRef, targetRef, withExtForWikiPage, branchOrTag, anchor), targetRef as? FileRef, ::LinkRef)
        }
    }

    fun wikiLinkHasRealExt(linkRef: LinkRef, targetRef: PathInfo): Boolean {
        return linkRef.hasExt && linkRef.fileNameNoExt.equals(linkRef.fileToLink(targetRef.fileNameNoExt), ignoreCase = true)
    }

    @Suppress("NAME_SHADOWING")
    override fun linkAddress(linkRef: LinkRef, targetRef: PathInfo, withExtForWikiPage: Boolean?, branchOrTag: String?, anchor: String?): String {
        assertContainingFile(linkRef)
        val normLinkRef = normalizedLinkRef(linkRef)

        // need to make sure that the extension in the link is a real extension and not part of the file name, otherwise it will add the .md extension erroneously
        val withExtForWikiPage = withExtForWikiPage ?: wikiLinkHasRealExt(normLinkRef, targetRef)

        if (targetRef is FileRef) {
            var prefix = relativePath(normLinkRef, targetRef, withExtForWikiPage, branchOrTag)

            if (normLinkRef is WikiLinkRef) {
                return prefix.suffixWith('/') + normLinkRef.fileToLink(if (withExtForWikiPage) targetRef.fileName else targetRef.fileNameNoExt) + (anchor ?: if (wasAnchorUsedInMatch(normLinkRef, targetRef)) "" else normLinkRef.anchor).prefixWith("#")
            } else {
                if (prefix.isNotEmpty() && targetRef.isUnderWikiDir) {
                    // if the prefix starts with the wiki dir change it to the generic wiki used in links
                    val wikiDirName = targetRef.wikiDir.substring(targetRef.mainRepoDir.length + 1).suffixWith('/')
                    if (containingFile.isUnderWikiDir && prefix.startsWith(wikiDirName)) prefix = "wiki/" + prefix.substring(wikiDirName.length)
                    else if (!containingFile.isUnderWikiDir && prefix.startsWith("../../" + wikiDirName)) prefix = "../../wiki/" + prefix.substring(("../../" + wikiDirName).length)
                }

                val selfRef = isSelfRef(normLinkRef, targetRef, withExtForWikiPage)
                val optimizedAnchor = (anchor ?: optimizedLinkAnchor(normLinkRef, targetRef, withExtForWikiPage)).prefixWith('#')

                if (targetRef.isWikiPage) {
                    if (selfRef) return optimizedAnchor
                    else {
                        val fileName = prefix.suffixWith('/') + if (!withExtForWikiPage) (if (targetRef.isWikiHomePage) "" else targetRef.fileNameNoExt) else targetRef.fileName
                        return normLinkRef.fileToLink(fileName).removeSuffix("/") + optimizedAnchor
                    }
                } else {
                    if (selfRef) return optimizedAnchor
                    else return normLinkRef.fileToLink(prefix.suffixWith('/') + targetRef.fileName) + optimizedAnchor
                }
            }
        } else if (targetRef.isURI) {
            // convert git hub links to relative links
            val remoteUrl = projectResolver.getVcsRoot(normLinkRef.containingFile)?.baseUrl
            if (remoteUrl != null) {
                assert(remoteUrl.startsWith("http://", "https://"), { "remote vcsRepoBase has to start with http:// or https://, instead got ${remoteUrl}" })

                if (targetRef.path.startsWith(remoteUrl.suffixWith('/'))) {
                    val fileName = targetRef.filePath.substring(remoteUrl.suffixWith('/').length)
                    if (fileName in GITHUB_NON_FILE_LINKS) {
                        return when {
                            containingFile.isWikiHomePage || containingFile.isUnderWikiDir -> "../" + fileName
                            else -> PathInfo.relativePath(containingFile.path, projectBasePath.suffixWith('/'), withPrefix = true) + "../../" + fileName
                        }
                    } else {
                        // TEST: conversion of remote links to link addresses for all files and from all source files wiki/Home.md, wiki/normal-file.md and Readme.md
                        if (fileName.startsWith("wiki/")) {
                            // trying for wiki page
                            val filePath = when {
                                containingFile.isWikiHomePage && normLinkRef is ImageLinkRef -> fileName
                                containingFile.isUnderWikiDir -> fileName.substring("wiki/".length)
                                else -> PathInfo.relativePath(containingFile.path, projectBasePath.suffixWith('/'), withPrefix = true).suffixWith("/") + "../../" + fileName
                            }
                            return filePath
                        } else {
                            // main repo file, if it starts with blob/something/ or raw/something then we can handle it
                            var repoPrefixPathPattern = ("^([^/]+)\\Q/\\E([^/]+)\\Q/\\E").toRegex()
                            if (fileName.matches(repoPrefixPathPattern)) {
                                val match = repoPrefixPathPattern.find(fileName)
                                if (match != null) {
                                    val oldGitHubLink = match.groups[0]
                                    val oldBranchOrTag = match.groups[1]
                                    // we throw out the branch if one is given to us or if linking from another file in the repo, its branch or tag will be used by GitHub
                                    var fileNamePart = fileName.substring(match.range.endInclusive + 1)
                                    val filePath = when {
                                        containingFile.isWikiHomePage -> "$oldGitHubLink/${branchOrTag ?: oldBranchOrTag ?: "master"}/" + fileNamePart
                                        containingFile.isUnderWikiDir -> "../$oldGitHubLink/${branchOrTag ?: oldBranchOrTag ?: "master"}/" + fileNamePart
                                        else -> PathInfo.relativePath(containingFile.path, projectBasePath.suffixWith('/'), withPrefix = true).suffixWith("/") + fileNamePart
                                    }
                                    return filePath
                                }
                            }
                        }
                    }
                }
            }
        }
        return ""
    }

    private fun assertContainingFile(linkRef: LinkRef) {
        assert(linkRef.containingFile.compareTo(containingFile) == 0, { "likRef containingFile differs from LinkResolver containingFile, need new Resolver for each containing file" })
    }

    fun optimizedLinkAnchor(linkRef: LinkRef, targetRef: FileRef, withExtForWikiPage: Boolean): String {
        assert(linkRef === normalizedLinkRef(linkRef))

        val anchorUsedInMatch = wasAnchorUsedInMatch(linkRef, targetRef)
        val selfRef = isSelfRef(linkRef, targetRef, withExtForWikiPage)

        return if (anchorUsedInMatch)
            (if (selfRef) "#" else "")
        else
            (if (selfRef) "#" + linkRef.anchor.orEmpty() else linkRef.anchorText)
    }

    fun isSelfRef(linkRef: LinkRef, targetRef: FileRef, withExtForWikiPage: Boolean): Boolean {
        assert(linkRef === normalizedLinkRef(linkRef))

        return if ((targetRef.isWikiPage && withExtForWikiPage) || (!targetRef.isWikiPage && linkRef.hasExt)) linkRef.containingFile.filePathNoExt.equals(targetRef.filePathNoExt)
        else linkRef.containingFile.filePath.equals(targetRef.filePath)
    }

    fun wasAnchorUsedInMatch(linkRef: LinkRef, targetRef: PathInfo): Boolean {
        assert(linkRef === normalizedLinkRef(linkRef))

        return linkRef.hasAnchor && (!linkRef.linkToFile(linkRef.fileName).equals(targetRef.fileName) && targetRef.fileName.endsWith(linkRef.anchorText)
                                     || !linkRef.linkToFile(linkRef.fileNameNoExt).equals(targetRef.fileNameNoExt) && targetRef.fileNameNoExt.endsWith(linkRef.anchorText))
    }

    fun equalLinks(fileName: String, wikiLinkAddress: String, ignoreCase: Boolean = true): Boolean {
        return WikiLinkRef.fileAsLink(fileName).equals(WikiLinkRef.fileAsLink(wikiLinkAddress), ignoreCase)
    }

    fun uriToResolvedRelativeLink(linkRef: LinkRef): LinkRef? {
        val normLinkRef = normalizedLinkRef(linkRef)

        val relLink = uriToRelativeLink(normLinkRef)
        if (relLink != null) {
            val targetRef = resolve(relLink, Want(Local.REF, Remote.REF), null)

            if (targetRef != null) {
                val relRef = linkRef(normLinkRef, targetRef, null, null, null);
                return relRef
            }
        }
        return null
    }

    fun uriToRelativeLink(linkRef: LinkRef): LinkRef? {
        if (!linkRef.isURI) return linkRef
        val normLinkRef = normalizedLinkRef(linkRef)

        if (normLinkRef.isLocal) {
            val targetRef = FileRef(normLinkRef.filePath.removePrefix("file://"))
            val relRef = normLinkRef.replaceFilePath(linkAddress(normLinkRef, targetRef, null, null, null), true)
            if (isResolvedTo(relRef, targetRef, null, null)) {
                return relRef
            }
        } else if (normLinkRef.isExternal) {
            val gitHubVcsRoot = projectResolver.getVcsRoot(normLinkRef.containingFile)
            if (gitHubVcsRoot != null) {
                var gitHubRepoBaseUrl = gitHubVcsRoot.baseUrl
                if (normLinkRef.filePath.startsWith(gitHubRepoBaseUrl)) {
                    var targetFilePath = gitHubVcsRoot.mainRepoBaseDir.suffixWith('/') + normLinkRef.linkToFile(normLinkRef.filePath.substring(gitHubRepoBaseUrl.suffixWith('/').length))
                    val containingFilePath = logicalRemotePath(containingFile, useWikiPageActualLocation = false, isSourceRef = true, isImageLinkRef = normLinkRef is ImageLinkRef, branchOrTag = null).filePath.suffixWith('/')

                    val fullPath = PathInfo.relativePath(containingFilePath, targetFilePath)
                    val relLink = normLinkRef.replaceFilePath(fullPath, false)
                    return relLink
                }
            }
        }
        return null
    }

    //    fun useRawAccess(linkRef: LinkRef): Boolean {
    //        assertContainingFile(linkRef)
    //        assert(linkRef === normalizedLinkRef(linkRef))
    //
    //        if (linkRef is ImageLinkRef || )
    //        // should have raw/ after path
    //        if (linkRef.isRelative) {
    //
    //        }
    //        else if (linkRef.filePath.startsWith("https://github.com/")) {
    //            val noRawContentPrefix = linkRef.filePath.removePrefix("https://raw.githubusercontent.com/")
    //            val linkParts = noRawContentPrefix.split("?", limit = 2)[0].split("/")
    //
    //            if (linkParts.size >= 3) {
    //                if (linkParts[2] in arrayOf("raw", "blob")) {
    //                    // we have a user and repo match
    //                    return linkParts[
    //                }
    //            }
    //        }
    //        return null
    //        return ""
    //    }

    fun normalizedLinkRef(linkRef: LinkRef): LinkRef {
        // from "https://raw.githubusercontent.com/vsch/idea-multimarkdown/master/assets/images/ScreenShot_source_preview.png"
        // to "https://github.com/vsch/idea-multimarkdown/raw/master/assets/images/ScreenShot_source_preview.png"
        // from "https://raw.githubusercontent.com/wiki/vsch/idea-multimarkdown/img/ScreenShot_source_preview_Large.png?token=AJ0mzve3jxMArvfYq7nKkL1ZaYZbPVxXks5Was-1wA%3D%3D"
        // to "https://github.com/vsch/idea-multimarkdown/wiki/img/ScreenShot_source_preview.png"
        if (linkRef.filePath.startsWith("https://raw.githubusercontent.com/")) {
            val noRawContentPrefix = linkRef.filePath.removePrefix("https://raw.githubusercontent.com/")
            val linkParts = noRawContentPrefix.split("?", limit = 2)[0].split("/")

            if (linkParts.size >= 3 && (linkParts[0] != "wiki" || linkParts.size >= 4)) {
                var normLinkParts: List<String>

                if (linkParts[0] == "wiki") {
                    // we have a user and repo match
                    normLinkParts = arrayListOf<String>(linkParts[1], linkParts[2], "wiki", *linkParts.subList(3, linkParts.size).toTypedArray())
                } else {
                    // we have a user and repo match
                    normLinkParts = arrayListOf<String>(linkParts[0], linkParts[1], "raw", *linkParts.subList(2, linkParts.size).toTypedArray())
                }

                val changedUrlPath = PathInfo.appendParts("https://github.com", normLinkParts).filePath
                val normalizedLinkRef = linkRef.replaceFilePath(changedUrlPath, true)
                return normalizedLinkRef
            }
        }
        return linkRef
    }
}


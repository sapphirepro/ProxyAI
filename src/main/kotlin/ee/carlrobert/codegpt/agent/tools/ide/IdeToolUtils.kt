package ee.carlrobert.codegpt.agent.tools.ide

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Shared utility functions for IDE-related tools.
 * Provides common operations like file access, line/column conversion, and IDE state checks.
 */
object IdeToolUtils {

    /**
     * Resolves the project to use. If [projectPath] is provided, attempts to find an open project
     * with that base path. Falls back to [defaultProject] if not found or if [projectPath] is null.
     *
     * @param projectPath Optional project path to search for
     * @param defaultProject Default project to use as fallback
     * @return The resolved project
     */
    fun getProject(projectPath: String?, defaultProject: Project): Project {
        if (projectPath == null) return defaultProject
        return ProjectManager.getInstance().openProjects.find {
            it.basePath == projectPath
        } ?: defaultProject
    }

    /**
     * Retrieves a PSI file from the project for the given relative path.
     * Safely handles PSI access by wrapping in a read-action.
     *
     * @param project The project context
     * @param relativePath Path relative to project root
     * @return The PSI file or null if not found
     */
    fun getPsiFile(project: Project, relativePath: String): PsiFile? {
        return runReadActionBlocking {
            val baseDir = project.baseDir ?: return@runReadActionBlocking null
            val vfile =
                baseDir.findFileByRelativePath(relativePath) ?: return@runReadActionBlocking null
            PsiManager.getInstance(project).findFile(vfile)
        }
    }
}

package it.panebarco.creator

import android.app.Application
import it.panebarco.creator.data.ProjectRepository
import java.io.File

class CreatorApplication : Application() {
    val repository: ProjectRepository by lazy { ProjectRepository(File(filesDir, "Projects")) }
}

package site.leos.apps.lespas.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File

class ActionViewModel(application: Application): AndroidViewModel(application) {
    private val actionRepository = ActionRepository(application)
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val localRootFolder = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"

    val allActions: LiveData<List<Action>> = actionRepository.pendingActionsFlow().asLiveData()
    fun addActions(actions: List<Action>) = viewModelScope.launch(Dispatchers.IO) { actionRepository.addActions(actions) }

    fun deleteAlbums(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.deleteAlbums(albums)

            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()
            albums.forEach {album->
                val allPhotoIds = photoRepository.getAllPhotoIdsByAlbum(album.id)
                photoRepository.deletePhotosByAlbum(album.id)
                allPhotoIds.forEach {
                    try {
                        File(localRootFolder, it.id).delete()
                    } catch(e: Exception) { e.printStackTrace() }
                    try {
                        File(localRootFolder, it.name).delete()
                    } catch(e: Exception) { e.printStackTrace() }
                }
                actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, album.id, album.name,"", "", timestamp,1))
            }
            actionRepository.addActions(actions)
        }
    }

    fun deletePhotos(photos: List<Photo>, albumName: String)  {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete from local database
            photoRepository.deletePhotos(photos)

            // Create new actions on server side
            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()

            photos.forEach {photo ->
                try {
                    File(localRootFolder, photo.id).delete()
                } catch (e: Exception) { e.printStackTrace() }
                try {
                    File(localRootFolder, photo.name).delete()
                } catch (e: Exception) { e.printStackTrace() }

                // folderName field can be blank in these actons
                actions.add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, photo.albumId, albumName, photo.id, photo.name, timestamp, 1))
            }

            // Get remaining photos in album, the return list is sort by dateTaken ASC
            val photosLeft = photoRepository.getAlbumPhotos(photos[0].albumId)
            if (photosLeft.isNotEmpty()) {
                val album = albumRepository.getThisAlbum(photos[0].albumId)
                album[0].startDate = photosLeft.first().dateTaken
                album[0].endDate = photosLeft.last().dateTaken
                albumRepository.updateSync(album[0])
            } else {
                // All photos under this album removed, delete album
                albumRepository.deleteByIdSync(photos[0].albumId)
                actions.clear()
                actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, photos[0].albumId, albumName, "", "", timestamp, 1))
            }

            actionRepository.addActions(actions)
        }
    }

    fun renameAlbum(albumId: String, oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!albumRepository.isAlbumExisted(newName)) {
                actionRepository.addActions(Action(null, Action.ACTION_RENAME_DIRECTORY, albumId, oldName, "", newName, System.currentTimeMillis(), 1))
                albumRepository.changeName(albumId, newName)
            }
        }
        // TODO how to properly inform user that name already existed?
    }
}
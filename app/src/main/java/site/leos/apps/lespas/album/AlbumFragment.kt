package site.leos.apps.lespas.album

import android.app.Activity
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.transition.TransitionInflater
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.view.ViewCompat
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.selection.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.photo.AlbumPhotoName
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class AlbumFragment : Fragment(), ActionMode.Callback, ConfirmDialogFragment.OnPositiveConfirmedListener {
    private var actionMode: ActionMode? = null
    private lateinit var mAdapter: AlbumListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: FloatingActionButton

    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var lastSelection: MutableSet<Long>
    private var lastScrollPosition = -1

    private val albumsModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private lateinit var acquiringModel: AcquiringDialogFragment.AcquiringViewModel
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastScrollPosition = savedInstanceState?.getInt(SCROLL_POSITION) ?: -1
        lastSelection = savedInstanceState?.getLongArray(SELECTION)?.toMutableSet() ?: mutableSetOf()

        postponeEnterTransition()
        sharedElementEnterTransition = TransitionInflater.from(context).inflateTransition(R.transition.album_to_albumdetail)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_album, container, false)
        recyclerView = view.findViewById(R.id.albumlist)
        fab = view.findViewById(R.id.fab)
        mAdapter = AlbumListAdapter(
            { album, imageView ->
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                    .replace(R.id.container_root, AlbumDetailFragment.newInstance(album)).addToBackStack(null).commit()
            }
        ) { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() } }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /*
        (view.parent as ViewGroup).also {
            it.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    it.viewTreeObserver.removeOnPreDrawListener(this)
                    startPostponedEnterTransition()
                    return false
                }
            })
        }

         */

        // Register data observer first, try feeding adapter with lastest data asap
        albumsModel.allAlbumsByEndDate.observe(viewLifecycleOwner, { albums ->
            mAdapter.setAlbums(albums,)
            if (lastScrollPosition != -1) {
                (recyclerView.layoutManager as LinearLayoutManager).scrollToPosition(lastScrollPosition)
            }
        })

        mAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            init {
                toggleEmptyView()
            }

            private fun toggleEmptyView() {
                if (mAdapter.itemCount == 0) {
                    recyclerView.visibility = View.GONE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.GONE
                }
            }

            override fun onChanged() {
                super.onChanged()
                toggleEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                toggleEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                toggleEmptyView()
            }
        })


        with(recyclerView) {
            // Stop item from blinking when notifying changes
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

            adapter = mAdapter

            selectionTracker = SelectionTracker.Builder(
                "albumSelection",
                this,
                AlbumListAdapter.AlbumKeyProvider(mAdapter),
                AlbumListAdapter.AlbumDetailsLookup(this),
                StorageStrategy.createLongStorage()
            ).withSelectionPredicate(SelectionPredicates.createSelectAnything()).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionTracker.selection.size())}
                        } else if (!selectionTracker.hasSelection() && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionTracker.selection.size())
                    }

                    override fun onItemStateChanged(key: Long, selected: Boolean) {
                        super.onItemStateChanged(key, selected)
                        if (selected) lastSelection.add(key)
                        else lastSelection.remove(key)
                    }
                })
            }

            mAdapter.setSelectionTracker(selectionTracker)

            // Restore selection state
            if (lastSelection.isNotEmpty()) lastSelection.forEach { selectionTracker.select(it) }

            // Get scroll position after scroll idle
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) lastScrollPosition = (layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                }
            })
        }


        fab.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, REQUEST_FOR_IMAGES)
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.app_name)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SCROLL_POSITION, lastScrollPosition)
        outState.putLongArray(SELECTION, lastSelection.toLongArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // TODO right place to do this?
        recyclerView.clearOnScrollListeners()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        val uris = ArrayList<Uri>()

        super.onActivityResult(requestCode, resultCode, intent)

        if (resultCode == Activity.RESULT_OK) {
            when(requestCode) {
                REQUEST_FOR_IMAGES-> {
                    intent?.clipData?.apply {for (i in 0..itemCount) uris.add(getItemAt(i).uri)} ?: uris.add(intent?.data!!)

                    if (uris.isNotEmpty()) {
                        destinationModel.getDestination().observe (this, { album->
                            // Acquire files
                            acquiringModel = ViewModelProvider(requireActivity(), AcquiringDialogFragment.AcquiringViewModelFactory(requireActivity().application, uris))
                                .get(AcquiringDialogFragment.AcquiringViewModel::class.java)
                            acquiringModel.getProgress().observe(this, { progress->
                                if (progress == uris.size) {
                                    // Files are under control, we can create sync action now
                                    val actions = mutableListOf<Action>()
                                    val newPhotos = acquiringModel.getNewPhotos()
                                    var allPhotoNameMap = emptyList<AlbumPhotoName>()

                                    runBlocking {
                                        allPhotoNameMap = GlobalScope.async(Dispatchers.Default) {
                                            albumsModel.getAllPhotoNameMap()
                                        }.await()
                                    }

                                    // Create new album first
                                    if (album.id.isEmpty()) {
                                        // Set a fake ID, sync adapter will correct it when real id is available
                                        album.id = System.currentTimeMillis().toString()

                                        // Store cover, e.g. first photo in new album, in member filename
                                        album.coverBaseline = (newPhotos[0].height - (newPhotos[0].width * 9 / 21)) / 2
                                        album.coverWidth = newPhotos[0].width
                                        album.coverHeight = newPhotos[0].height
                                        actions.add(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[0].name, System.currentTimeMillis(), 1))
                                    }

                                    newPhotos.forEach {
                                        it.albumId = album.id

                                        // Is there a photo with same name exists in the same album?
                                        if (allPhotoNameMap.contains(AlbumPhotoName(it.albumId, it.name))) it.id = ""

                                        if (it.dateTaken < album.startDate) album.startDate = it.dateTaken
                                        if (it.dateTaken > album.endDate) album.endDate = it.dateTaken
                                        actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, album.id, album.name, "", it.name, System.currentTimeMillis(), 1))
                                    }

                                    actionModel.addActions(actions)
                                    GlobalScope.launch(Dispatchers.Default) {
                                        // If photo with same exists, don't duplicate it
                                        val addPhotos = mutableListOf<Photo>()
                                        newPhotos.forEach { if (it.id.isNotEmpty()) addPhotos.add(it) }

                                        albumsModel.addPhotos(newPhotos)
                                        albumsModel.upsertAsync(album)
                                    }

                                }
                            })

                            if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                                AcquiringDialogFragment.newInstance(uris).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                        })

                        if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                            DestinationDialogFragment.newInstance().show(parentFragmentManager, TAG_DESTINATION_DIALOG)

                    }
                }
            }
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.actions_delete_and_share, menu)
        fab.isEnabled = false

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        menu?.removeItem(R.id.share)

        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete)).let {
                    it.setTargetFragment(this, 0)
                    it.show(parentFragmentManager, "CONFIRM_DIALOG")
                }
                true
            }
            R.id.share -> {
                selectionTracker.selection.forEach { _ -> }

                selectionTracker.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
        fab.isEnabled = true
    }

    override fun onPositiveConfirmed() {
        val albums = mutableListOf<Album>()
        // Selection key is Album.id
        for (i in selectionTracker.selection) albums.add(mAdapter.getItemBySelectionKey(i))
        actionModel.deleteAlbums(albums)

        selectionTracker.clearSelection()
    }

    // List adapter for Albums' recyclerView
    class AlbumListAdapter(private val itemClickListener: OnItemClickListener, private val imageLoader: OnLoadImage): RecyclerView.Adapter<AlbumListAdapter.AlbumViewHolder>() {
        private var albums = emptyList<Album>()
        private var oldAlbums = mutableListOf<Album>()
        private var covers = mutableListOf<Photo>()
        private lateinit var selectionTracker: SelectionTracker<Long>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })

        fun interface OnItemClickListener {
            fun onItemClick(album: Album, imageView: ImageView)
        }

        fun interface OnLoadImage {
            fun loadImage(photo: Photo, view: ImageView, type: String)
        }

        inner class AlbumViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(album: Album, clickListener: OnItemClickListener, isActivated: Boolean) {
                itemView.apply {
                    this.isActivated = isActivated
                    findViewById<ImageView>(R.id.coverart).let {coverImageview ->
                        imageLoader.loadImage(covers[adapterPosition], coverImageview, ImageLoaderViewModel.TYPE_COVER)
                        if (this.isActivated) coverImageview.colorFilter = selectedFilter
                        else coverImageview.clearColorFilter()
                        ViewCompat.setTransitionName(coverImageview, album.id)
                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener.onItemClick(album, coverImageview) }
                        if (album.eTag.isEmpty()) {
                            coverImageview.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(album.syncProgress) })
                            with(findViewById<ContentLoadingProgressBar>(R.id.sync_progress)) {
                                visibility = View.VISIBLE
                                setProgress((album.syncProgress * 100).toInt())
                            }
                        } else {
                            coverImageview.clearColorFilter()
                            findViewById<ContentLoadingProgressBar>(R.id.sync_progress).visibility = View.GONE
                        }
                    }
                    findViewById<TextView>(R.id.title).text = album.name
                    findViewById<TextView>(R.id.duration).text = String.format(
                        "%s  -  %s",
                        album.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        album.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    )
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long = getItemId(adapterPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder  {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false)
            return AlbumViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            holder.bindViewItems(albums[position], itemClickListener, selectionTracker.isSelected(getItemId(position)))
        }

        internal fun setAlbums(albums: List<Album>) {
            oldAlbums.let {
                it.clear()
                it.addAll(0, this.albums)
            }
            this.albums = albums
            this.covers.apply {
                clear()
                albums.forEach { album ->
                    this.add(Photo(album.cover, album.id, "", "", LocalDateTime.now(), LocalDateTime.now(), album.coverWidth, album.coverHeight, album.coverBaseline))
                }
            }
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldAlbums.size
                override fun getNewListSize() = albums.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = oldAlbums[oldItemPosition].id == albums[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = oldAlbums[oldItemPosition] == albums[newItemPosition]
            }).dispatchUpdatesTo(this)
        }

        internal fun getItemBySelectionKey(key: Long): Album = (albums.find { it.id.toLong() == key })!!

        override fun getItemCount() = albums.size

        override fun getItemId(position: Int): Long = albums[position].id.toLong()

        fun getPosition(key: Long): Int = albums.indexOfFirst { it.id.toLong() == key}

        fun setSelectionTracker(selectionTracker: SelectionTracker<Long>) { this.selectionTracker = selectionTracker }

        class AlbumKeyProvider(private val adapter: AlbumListAdapter): ItemKeyProvider<Long>(SCOPE_CACHED) {
            override fun getKey(position: Int): Long = adapter.getItemId(position)
            override fun getPosition(key: Long): Int = adapter.getPosition(key)
        }

        class AlbumDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<Long>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    return (recyclerView.getChildViewHolder(it) as AlbumViewHolder).getItemDetails()
                }
                return null
            }
        }
    }

    companion object {
        const val REQUEST_FOR_IMAGES = 1111
        const val TAG_ACQUIRING_DIALOG = "ALBUMFRAGMENT_TAG_ACQUIRING_DIALOG"
        const val TAG_DESTINATION_DIALOG = "ALBUMFRAGMENT_TAG_DESTINATION_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SCROLL_POSITION = "SCROLL_POSITION"
        private const val SELECTION = "SELECTION"

        fun newInstance() = AlbumFragment()
    }
}
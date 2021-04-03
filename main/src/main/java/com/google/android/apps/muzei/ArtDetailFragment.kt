/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei

import android.animation.Animator
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.app.RemoteActionCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.api.internal.RemoteActionBroadcastReceiver
import com.google.android.apps.muzei.legacy.BuildConfig.LEGACY_AUTHORITY
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver
import com.google.android.apps.muzei.render.ArtworkSizeStateFlow
import com.google.android.apps.muzei.render.ContentUriImageLoader
import com.google.android.apps.muzei.render.SwitchingPhotosDone
import com.google.android.apps.muzei.render.SwitchingPhotosInProgress
import com.google.android.apps.muzei.render.SwitchingPhotosStateFlow
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.settings.AboutActivity
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.android.apps.muzei.widget.showWidgetPreview
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.ArtDetailFragmentBinding
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Since [ArtDetailOpen] is global, track whether any instance of it is open globally.
 * Add [OpenTracker] as an observer to track.
 */
private object OpenTracker : DefaultLifecycleObserver {
    private val openCount = AtomicInteger(0)
    val isOpen = MutableStateFlow(false)

    override fun onStart(owner: LifecycleOwner) {
        if (openCount.getAndIncrement() == 0) {
            isOpen.value = true
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (openCount.decrementAndGet() == 0) {
            isOpen.value = false
        }
    }
}

val ArtDetailOpen: StateFlow<Boolean> get() = OpenTracker.isOpen

private fun TextView.setTextOrGone(text: String?) {
    if (text?.isNotEmpty() == true) {
        this.text = text
        isVisible = true
    } else {
        isGone = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun View.clicks(): Flow<Unit> = callbackFlow {
    setOnClickListener { offer(Unit) }
    try {
        awaitCancellation()
    } finally {
        setOnClickListener(null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun SubsamplingScaleImageView.imageLoadedEvents(): Flow<Unit> = callbackFlow {
    setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
        override fun onImageLoaded() {
            offer(Unit)
        }
    })
    try {
        awaitCancellation()
    } finally {
        setOnImageEventListener(null)
    }
}

/**
 * Animate a view to [View.VISIBLE] if [visible] == `true` or [View.GONE] if [visible] == `false`.
 * [block] should apply any desired animation attributes to the [ViewPropertyAnimator] receiver.
 * Returns normally when the animation completes normally or throws
 * [kotlinx.coroutines.CancellationException] if the animation is interrupted/otherwise cancelled.
 *
 * See [awaitEnd].
 */
private suspend inline fun View.animateVisibility(
    visible: Boolean,
    block: ViewPropertyAnimator.() -> Unit
) {
    isVisible = true
    animate().apply(block).awaitEnd()
    if (!visible) {
        isGone = true
    }
}

/**
 * Await the end of the animation performed by this [ViewPropertyAnimator].
 * Returns normally if the animation completes normally or throws
 * [kotlinx.coroutines.CancellationException] if the animation is interrupted/otherwise cancelled.
 */
private suspend fun ViewPropertyAnimator.awaitEnd() {
    suspendCancellableCoroutine<Unit> { co ->
        setListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator?) {}

            override fun onAnimationEnd(animation: Animator?) {
                co.resume(Unit)
            }

            override fun onAnimationCancel(animation: Animator?) {
                co.cancel()
            }

            override fun onAnimationRepeat(animation: Animator?) {}
        })
        co.invokeOnCancellation { cancel() }
    }
}

/**
 * Animate chrome views of this [ArtDetailFragmentBinding] to [View.VISIBLE] if [visible] == `true`
 * or [View.GONE] if [visible] == `false`.
 * Returns normally if the animation completes normally or throws
 * [kotlinx.coroutines.CancellationException] if the animation is interrupted/otherwise cancelled.
 */
private suspend fun ArtDetailFragmentBinding.animateChromeVisibility(
    visible: Boolean,
    metadataSlideDistance: Float
): Unit = coroutineScope {
    launch(start = CoroutineStart.UNDISPATCHED) {
        scrim.animateVisibility(visible) {
            alpha(if (visible) 1f else 0f)
            duration = 200
        }
    }

    chromeContainer.animateVisibility(visible) {
        alpha(if (visible) 1f else 0f)
        translationY(if (visible) 0f else metadataSlideDistance)
        duration = 200
    }
}

private inline fun <T> MutableStateFlow<T>.update(
    updater: (T) -> T
) {
    do {
        val old = value
        val new = updater(old)
    } while (!compareAndSet(old, new))
}

/**
 * Manage the animation and visibility of an [ArtDetailFragmentBinding]'s loading indicator.
 * Call [run] to manage visibility for a binding.
 * Call [show] to track a task that a loading indicator should be shown for.
 */
private class LoadingAnimator {
    private val refCount = MutableStateFlow(0)

    /**
     * Perform a task that should display a loading indicator while it runs.
     * One or more calls to [run] must be in progress for this to have a visible effect.
     */
    suspend fun <R> show(block: suspend () -> R): R {
        refCount.update { it + 1 }
        return try {
            block()
        } finally {
            refCount.update { it - 1 }
        }
    }

    /**
     * Manage the display of the loading indication for [binding].
     * There should only be one active call to [run] for any given value of [binding] at a time.
     * Multiple calls to [run] for **different** [binding]s may run concurrently.
     */
    suspend fun run(binding: ArtDetailFragmentBinding): Nothing {
        with(binding) {
            refCount.map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { visible ->
                    if (visible) {
                        delay(700)
                        imageLoadingIndicator.start()
                        imageLoadingContainer.animateVisibility(true) {
                            alpha(1f)
                            duration = 300
                        }
                    } else {
                        if (currentCoroutineContext().isActive) {
                            imageLoadingContainer.animateVisibility(false) {
                                alpha(0f)
                                duration = 1000
                            }
                        } else {
                            imageLoadingContainer.isGone = true
                        }
                        imageLoadingIndicator.stop()
                    }
                }
        }
        error("MutableStateFlow never completes")
    }
}

class ArtDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MuzeiDatabase.getInstance(application)

    val currentProvider = database.providerDao().currentProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val currentArtwork = database.artworkDao().currentArtwork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArtDetailFragment : Fragment(R.layout.art_detail_fragment) {

    companion object {
        private const val KEY_IMAGE_VIEW_STATE = "IMAGE_VIEW_STATE"
        private val SOURCE_ACTION_IDS = intArrayOf(
            R.id.source_action_1,
            R.id.source_action_2,
            R.id.source_action_3,
            R.id.source_action_4,
            R.id.source_action_5,
            R.id.source_action_6,
            R.id.source_action_7,
            R.id.source_action_8,
            R.id.source_action_9,
            R.id.source_action_10
        )
    }

    // The fragment has no domain mutable state; only a reference to the ViewModel
    // and these cached values computed from the context.
    private val showBackgroundImage by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && requireActivity().isInMultiWindowMode
    }
    private val metadataSlideDistance by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
    }
    private val viewModel: ArtDetailViewModel by viewModels()

    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                NewWallpaperNotificationReceiver.markNotificationRead(requireContext())
            }
        }
        lifecycle.addObserver(OpenTracker)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        showHideChrome(true)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = ArtDetailFragmentBinding.bind(view)
        // View creation is an instantaneous event, but a binding is an ongoing process.
        viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            binding.bind(viewLifecycleOwner.lifecycle)
        }
    }

    private fun showHideChrome(show: Boolean) {
        requireActivity().window.decorView.apply {
            var flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            flags = flags or if (show) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
            flags = flags or (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            if (!show) {
                flags = flags or (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE)
            }
            systemUiVisibility = flags
        }
    }

    private fun onMenuItemClick(
        menuItem: MenuItem,
        action: RemoteActionCompat?
    ): Boolean {
        val context = context ?: return false
        if (action != null) {
            viewModel.currentArtwork.value?.run {
                Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                    param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerAuthority)
                    param(FirebaseAnalytics.Param.ITEM_NAME, menuItem.title.toString())
                    param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, "art_detail")
                }
                try {
                    action.actionIntent.send()
                } catch (e: PendingIntent.CanceledException) {
                    // Why do you give us a cancelled PendingIntent.
                    // We can't do anything with that.
                }
            }
            return true
        }

        return when (menuItem.itemId) {
            R.id.action_gestures -> {
                Firebase.analytics.logEvent("gestures_open", null)
                findNavController().navigate(ArtDetailFragmentDirections.gestures())
                true
            }
            R.id.action_always_dark -> {
                val alwaysDark = !menuItem.isChecked
                menuItem.isChecked = alwaysDark
                Firebase.analytics.logEvent("always_dark") {
                    param(FirebaseAnalytics.Param.VALUE, alwaysDark.toString())
                }
                MuzeiApplication.setAlwaysDark(context, alwaysDark)
                true
            }
            R.id.action_about -> {
                Firebase.analytics.logEvent("about_open", null)
                startActivity(Intent(context, AboutActivity::class.java))
                true
            }
            else -> false
        }
    }

    /**
     * Maintain a live binding of application data to an [ArtDetailFragmentBinding].
     */
    private suspend fun ArtDetailFragmentBinding.bind(
        lifecycle: Lifecycle
    ): Unit = coroutineScope {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scrim.background = makeCubicGradientScrimDrawable(Gravity.TOP, 0x44)
        }

        val scrimColor = resources.getInteger(R.integer.scrim_channel_color)
        chromeContainer.background = makeCubicGradientScrimDrawable(Gravity.BOTTOM, 0xAA,
            scrimColor, scrimColor, scrimColor)

        root.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0
            launch { animateChromeVisibility(visible, metadataSlideDistance) }
        }

        title.typeface = ResourcesCompat.getFont(requireContext(), R.font.alegreya_sans_black)
        byline.typeface = ResourcesCompat.getFont(requireContext(), R.font.alegreya_sans_medium)

        val overflowSourceActionMap = SparseArray<RemoteActionCompat>()
        overflowMenu.setOnMenuItemClickListener { menuItem ->
            onMenuItemClick(menuItem, overflowSourceActionMap.get(menuItem.itemId))
        }
        launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    awaitCancellation()
                } finally {
                    overflowMenu.hideOverflowMenu()
                }
            }
        }

        // Ensure that when the view state is saved, the SubsamplingScaleImageView also
        // has its state saved
        root.findViewTreeSavedStateRegistryOwner()
            ?.savedStateRegistry
            ?.registerSavedStateProvider(KEY_IMAGE_VIEW_STATE) {
                val backgroundImage =
                    backgroundImageContainer[backgroundImageContainer.displayedChild]
                            as SubsamplingScaleImageView
                Bundle().apply {
                    putSerializable(KEY_IMAGE_VIEW_STATE, backgroundImage.state)
                }
            }

        var showChrome = true
        backgroundImageContainer.isVisible = showBackgroundImage
        backgroundImageContainer.children.forEachIndexed { index, img ->
            val backgroundImage = img as SubsamplingScaleImageView
            backgroundImage.apply {
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
                launch {
                    imageLoadedEvents().collect {
                        // Only update the displayedChild when the image has finished loading
                        backgroundImageContainer.displayedChild = index
                    }
                }
                launch {
                    clicks().collectLatest {
                        showChrome = !showChrome
                        animateChromeVisibility(showChrome, metadataSlideDistance)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setOnLongClickListener {
                        launch { showWidgetPreview(requireContext().applicationContext) }
                        true
                    }
                }
            }
        }

        var currentViewportId = 0
        val loadingAnimator = LoadingAnimator()
        launch { loadingAnimator.run(this@bind) }

        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                TooltipCompat.setTooltipText(nextArtwork, nextArtwork.contentDescription)
                viewModel.currentProvider.collectLatest { provider ->
                    val supportsNextArtwork = provider?.supportsNextArtwork == true
                    nextArtwork.isVisible = supportsNextArtwork
                    nextArtwork.clicks().collectLatest {
                        Firebase.analytics.logEvent("next_artwork") {
                            param(FirebaseAnalytics.Param.CONTENT_TYPE, "art_detail")
                        }
                        val currentArt = viewModel.currentArtwork.value
                        ProviderManager.getInstance(requireContext()).nextArtwork()
                        withTimeoutOrNull(10_000) {
                            loadingAnimator.show {
                                viewModel.currentArtwork.filter { it != currentArt }.first()
                            }
                        }
                    }
                }
            }

            var wallpaperAspectRatio = 0f
            var artworkAspectRatio = 0f
            var deferResetViewport = false

            fun resetProxyViewport() {
                if (wallpaperAspectRatio == 0f || artworkAspectRatio == 0f) {
                    return
                }

                deferResetViewport = false
                if (SwitchingPhotosStateFlow.value is SwitchingPhotosInProgress) {
                    deferResetViewport = true
                    return
                }

                panScaleProxy.relativeAspectRatio = artworkAspectRatio / wallpaperAspectRatio
            }

            launch {
                WallpaperSizeStateFlow.filterNotNull().collect { size ->
                    wallpaperAspectRatio = if (size.height > 0) {
                        size.width * 1f / size.height
                    } else {
                        panScaleProxy.width * 1f / panScaleProxy.height
                    }
                    resetProxyViewport()
                }
            }

            launch {
                ArtworkSizeStateFlow.filterNotNull().collect { size ->
                    artworkAspectRatio = size.width * 1f / size.height
                    resetProxyViewport()
                }
            }

            launch {
                var guardViewportChangeListener = false
                panScaleProxy.apply {
                    // Don't show the PanScaleProxyView when the background image is visible
                    isVisible = !showBackgroundImage
                    setMaxZoom(5)
                    onViewportChanged = {
                        if (!guardViewportChangeListener) {
                            ArtDetailViewport.setViewport(
                                currentViewportId, panScaleProxy.currentViewport, true)
                        }
                    }
                    onSingleTapUp = {
                        activity?.window?.let {
                            showHideChrome(it.decorView.systemUiVisibility and
                                    View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        onLongPress = {
                            launch { showWidgetPreview(requireContext().applicationContext) }
                        }
                    }
                }
                ArtDetailViewport.getChanges().collect { isFromUser ->
                    if (!isFromUser) {
                        guardViewportChangeListener = true
                        panScaleProxy.setViewport(ArtDetailViewport.getViewport(currentViewportId))
                        guardViewportChangeListener = false
                    }
                }
            }

            launch {
                SwitchingPhotosStateFlow.filterNotNull()
                    .collect { switchingPhotos ->
                        currentViewportId = switchingPhotos.viewportId
                        panScaleProxy.panScaleEnabled = switchingPhotos is SwitchingPhotosDone
                        // Process deferred artwork size change when done switching
                        if (switchingPhotos is SwitchingPhotosDone && deferResetViewport) {
                            resetProxyViewport()
                        }
                    }
            }

            viewModel.currentArtwork.collectLatest { currentArtwork ->
                title.setTextOrGone(currentArtwork?.title)
                byline.setTextOrGone(currentArtwork?.byline)
                attribution.setTextOrGone(currentArtwork?.attribution)

                coroutineScope {
                    launch {
                        metadata.clicks().collectLatest {
                            val context = requireContext()
                            Firebase.analytics.logEvent("artwork_info_open") {
                                param(FirebaseAnalytics.Param.CONTENT_TYPE, "art_detail")
                            }
                            viewModel.currentArtwork.value?.openArtworkInfo(context)
                        }
                    }

                    if (backgroundImageContainer.isVisible) {
                        launch {
                            val nextId = (backgroundImageContainer.displayedChild + 1) % 2
                            val orientation = withContext(Dispatchers.IO) {
                                ContentUriImageLoader(
                                    requireContext().contentResolver,
                                    MuzeiContract.Artwork.CONTENT_URI
                                ).getRotation()
                            }
                            val backgroundImage = backgroundImageContainer[nextId]
                                    as SubsamplingScaleImageView
                            backgroundImage.orientation = orientation
                            // Try to restore any saved state of the SubsamplingScaleImageView
                            // This would normally only be available after onViewStateRestored(),
                            // but this is within coroutine that is only launched when STARTED
                            val backgroundImageViewState =
                                root.findViewTreeSavedStateRegistryOwner()
                                    ?.savedStateRegistry
                                    ?.consumeRestoredStateForKey(KEY_IMAGE_VIEW_STATE)
                                    ?.getSerializable(KEY_IMAGE_VIEW_STATE) as ImageViewState?
                            backgroundImage.setImage(
                                ImageSource.uri(MuzeiContract.Artwork.CONTENT_URI),
                                backgroundImageViewState
                            )
                            // Set the image to visible since SubsamplingScaleImageView does some of
                            // its processing in onDraw()
                            backgroundImage.isVisible = true
                        }
                    }

                    val commands = context?.run {
                        currentArtwork?.getCommands(this)
                            ?: run {
                                if (viewModel.currentProvider.value?.authority == LEGACY_AUTHORITY) {
                                    listOf(
                                        RemoteActionCompat(
                                            IconCompat.createWithResource(
                                                context,
                                                R.drawable.ic_next_artwork
                                            ),
                                            getString(R.string.action_next_artwork),
                                            getString(R.string.action_next_artwork),
                                            RemoteActionBroadcastReceiver.createPendingIntent(
                                                this, LEGACY_AUTHORITY, id.toLong(), id
                                            )
                                        )
                                    )
                                } else {
                                    listOf()
                                }
                            }
                    }
                    val activity = activity
                    if (commands != null && activity != null) {
                        overflowSourceActionMap.clear()
                        overflowMenu.menu.clear()
                        activity.menuInflater.inflate(
                            R.menu.muzei_overflow,
                            overflowMenu.menu
                        )
                        overflowMenu.menu.findItem(R.id.action_always_dark)?.isChecked =
                            MuzeiApplication.getAlwaysDark(activity)
                        commands.filterNot { action ->
                            action.title.isBlank()
                        }.take(SOURCE_ACTION_IDS.size).forEachIndexed { i, action ->
                            overflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action)
                            overflowMenu.menu.add(0, SOURCE_ACTION_IDS[i], 0, action.title).apply {
                                isEnabled = action.isEnabled
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    contentDescription = action.contentDescription
                                }
                                if (action.shouldShowIcon()) {
                                    icon = action.icon.loadDrawable(activity)
                                    setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

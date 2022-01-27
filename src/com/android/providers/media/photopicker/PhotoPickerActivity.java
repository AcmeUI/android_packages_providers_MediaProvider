/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.photopicker;

import static com.android.providers.media.photopicker.data.PickerResult.getPickerResponseIntent;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_PHOTOS_TAB;

import android.annotation.IntDef;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.ui.AlbumsTabFragment;
import com.android.providers.media.photopicker.ui.PhotosTabFragment;
import com.android.providers.media.photopicker.ui.PreviewFragment;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback;
import com.google.android.material.chip.Chip;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {

    private static final String TAG =  "PhotoPickerActivity";
    private static final String EXTRA_TAB_CHIP_TYPE = "tab_chip_type";
    private static final int TAB_CHIP_TYPE_PHOTOS = 0;
    private static final int TAB_CHIP_TYPE_ALBUMS = 1;

    private static final float BOTTOM_SHEET_PEEK_HEIGHT_PERCENTAGE = 0.60f;

    @IntDef(prefix = { "TAB_CHIP_TYPE" }, value = {
            TAB_CHIP_TYPE_PHOTOS,
            TAB_CHIP_TYPE_ALBUMS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TabChipType {}

    private PickerViewModel mPickerViewModel;
    private Selection mSelection;
    private ViewGroup mTabChipContainer;
    private Chip mPhotosTabChip;
    private Chip mAlbumsTabChip;
    private BottomSheetBehavior mBottomSheetBehavior;
    private View mBottomSheetView;
    private View mFragmentContainerView;
    private View mDragBar;
    private View mPrivacyText;
    private Toolbar mToolbar;

    @TabChipType
    private int mSelectedTabChipType;

    @ColorInt
    private int mDefaultBackgroundColor;

    @ColorInt
    private int mToolBarIconColor;

    private int mToolbarHeight = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // We use the device default theme as the base theme. Apply the material them for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        super.onCreate(savedInstanceState);

        if (!isPhotoPickerEnabled()) {
            setCancelledResultAndFinishSelf();
        }

        setContentView(R.layout.activity_photo_picker);

        mToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        final int[] attrs = new int[]{R.attr.actionBarSize, R.attr.pickerTextColor};
        final TypedArray ta = obtainStyledAttributes(attrs);
        // Save toolbar height so that we can use it as padding for FragmentContainerView
        mToolbarHeight = ta.getDimensionPixelSize(/* index */ 0, /* defValue */ -1);
        mToolBarIconColor = ta.getColor(/* index */ 1,/* defValue */ -1);
        ta.recycle();

        mDefaultBackgroundColor = getColor(R.color.picker_background_color);
        mPickerViewModel = createViewModel();
        mSelection = mPickerViewModel.getSelection();

        try {
            mPickerViewModel.parseValuesFromIntent(getIntent());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Finished activity due to an exception while parsing extras", e);
            setCancelledResultAndFinishSelf();
        }

        mDragBar = findViewById(R.id.drag_bar);
        mPrivacyText = findViewById(R.id.privacy_text);

        mTabChipContainer = findViewById(R.id.chip_container);
        initTabChips();
        initBottomSheetBehavior();
        restoreState(savedInstanceState);

        // Save the fragment container layout so that we can adjust the padding based on preview or
        // non-preview mode.
        mFragmentContainerView = findViewById(R.id.fragment_container);
    }

    /**
     * TODO(b/205291616) Remove this before launch. This is a temporary method to hide the API
     * until we are ready to launch it.
     */
    @VisibleForTesting
    public boolean isPhotoPickerEnabled() {
        // Always enabled on T+
        if (SdkLevel.isAtLeastT()) {
            return true;
        }

        // If the system property is enabled, then picker is enabled
        boolean isSysPropertyEnabled =
                SystemProperties.getBoolean(
                        "persist.sys.storage_picker_enabled" /* key */,
                        false /* def */);
        if (isSysPropertyEnabled) {
            return true;
        }

        // If build is < S, then picker is disabled since we cannot check device config
        if (!SdkLevel.isAtLeastS()) {
            // We cannot read device config on R
            return false;
        }

        // If the device config is enabled, then picker is enabled
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                    "picker_intent_enabled",
                    false /* defaultValue */ );
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Warning: This method is needed for tests, we are not customizing anything here.
     * Allowing ourselves to control ViewModel creation helps us mock the ViewModel for test.
     */
    @VisibleForTesting
    @NonNull
    protected PickerViewModel createViewModel() {
        return new ViewModelProvider(this).get(PickerViewModel.class);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event){
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {

                Rect outRect = new Rect();
                mBottomSheetView.getGlobalVisibleRect(outRect);

                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        getSupportActionBar().setTitle(title);
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     *
     * @param state Bundle to save state
     */
    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(EXTRA_TAB_CHIP_TYPE, mSelectedTabChipType);
        saveBottomSheetState();
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            final int tabChipType = savedInstanceState.getInt(EXTRA_TAB_CHIP_TYPE,
                    TAB_CHIP_TYPE_PHOTOS);
            mSelectedTabChipType = tabChipType;
            if (tabChipType == TAB_CHIP_TYPE_PHOTOS) {
                if (PreviewFragment.get(getSupportFragmentManager()) == null) {
                    onTabChipClick(mPhotosTabChip);
                } else {
                    // PreviewFragment is shown
                    mPhotosTabChip.setSelected(true);
                }
            } else { // CHIP_TYPE_ALBUMS
                if (PhotosTabFragment.get(getSupportFragmentManager()) == null) {
                    onTabChipClick(mAlbumsTabChip);
                } else {
                    // PreviewFragment or PhotosTabFragment with category is shown
                    mAlbumsTabChip.setSelected(true);
                }
            }
            restoreBottomSheetState();
        } else {
            // This is the first launch, set the default behavior. Hide the title, show the chips
            // and show the PhotosTabFragment
            updateCommonLayouts(MODE_PHOTOS_TAB, /* title */ "");
            onTabChipClick(mPhotosTabChip);
            saveBottomSheetState();
        }
    }

    private static Chip generateTabChip(LayoutInflater inflater, ViewGroup parent, String title) {
        final Chip chip = (Chip) inflater.inflate(R.layout.picker_chip_tab_header, parent, false);
        chip.setText(title);
        return chip;
    }

    private void initTabChips() {
        initPhotosTabChip();
        initAlbumsTabChip();
    }

    private void initBottomSheetBehavior() {
        mBottomSheetView = findViewById(R.id.bottom_sheet);
        mBottomSheetBehavior = BottomSheetBehavior.from(mBottomSheetView);
        initStateForBottomSheet();

        mBottomSheetBehavior.addBottomSheetCallback(createBottomSheetCallBack());
        setRoundedCornersForBottomSheet();
    }

    private BottomSheetCallback createBottomSheetCallBack() {
        return new BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    finish();
                }
                saveBottomSheetState();
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
            }
        };
    }

    private void setRoundedCornersForBottomSheet() {
        final float cornerRadius =
                getResources().getDimensionPixelSize(R.dimen.picker_top_corner_radius);
        final ViewOutlineProvider viewOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        (int)(view.getHeight() + cornerRadius), cornerRadius);
            }
        };
        mBottomSheetView.setOutlineProvider(viewOutlineProvider);
    }

    private void initStateForBottomSheet() {
        if (!mSelection.canSelectMultiple() && !isOrientationLandscape()) {
            final int peekHeight = getBottomSheetPeekHeight(this);
            mBottomSheetBehavior.setPeekHeight(peekHeight);
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            mBottomSheetBehavior.setSkipCollapsed(true);
        }
    }

    private static int getBottomSheetPeekHeight(Context context) {
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect displayBounds = windowManager.getCurrentWindowMetrics().getBounds();
        return (int) (displayBounds.height() * BOTTOM_SHEET_PEEK_HEIGHT_PERCENTAGE);
    }

    private void restoreBottomSheetState() {
        // BottomSheet is always EXPANDED for landscape
        if (isOrientationLandscape()) {
            return;
        }
        final int savedState = mPickerViewModel.getBottomSheetState();
        if (isValidBottomSheetState(savedState)) {
            mBottomSheetBehavior.setState(savedState);
        }
    }

    private void saveBottomSheetState() {
        // Do not save state for landscape or preview mode. This is because they are always in
        // STATE_EXPANDED state.
        if (isOrientationLandscape() || !mBottomSheetView.getClipToOutline()) {
            return;
        }
        mPickerViewModel.setBottomSheetState(mBottomSheetBehavior.getState());
    }

    private boolean isValidBottomSheetState(int state) {
        return state == BottomSheetBehavior.STATE_COLLAPSED ||
                state == BottomSheetBehavior.STATE_EXPANDED;
    }

    private boolean isOrientationLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void initPhotosTabChip() {
        if (mPhotosTabChip == null) {
            mPhotosTabChip = generateTabChip(getLayoutInflater(), mTabChipContainer,
                    getString(R.string.picker_photos));
            mTabChipContainer.addView(mPhotosTabChip);
            mPhotosTabChip.setOnClickListener(this::onTabChipClick);
            mPhotosTabChip.setTag(TAB_CHIP_TYPE_PHOTOS);
        }
    }

    private void initAlbumsTabChip() {
        if (mAlbumsTabChip == null) {
            mAlbumsTabChip = generateTabChip(getLayoutInflater(), mTabChipContainer,
                    getString(R.string.picker_albums));
            mTabChipContainer.addView(mAlbumsTabChip);
            mAlbumsTabChip.setOnClickListener(this::onTabChipClick);
            mAlbumsTabChip.setTag(TAB_CHIP_TYPE_ALBUMS);
        }
    }

    private void onTabChipClick(@NonNull View view) {
        final int chipType = (int) view.getTag();
        mSelectedTabChipType = chipType;

        // Check whether the tabChip is already selected or not. If it is selected, do nothing
        if (view.isSelected()) {
            return;
        }

        if (chipType == TAB_CHIP_TYPE_PHOTOS) {
            mPhotosTabChip.setSelected(true);
            mAlbumsTabChip.setSelected(false);
            PhotosTabFragment.show(getSupportFragmentManager(), Category.getDefaultCategory());
        } else { // CHIP_TYPE_ALBUMS
            mPhotosTabChip.setSelected(false);
            mAlbumsTabChip.setSelected(true);
            AlbumsTabFragment.show(getSupportFragmentManager());
        }
    }

    public void setResultAndFinishSelf() {
        setResult(Activity.RESULT_OK, getPickerResponseIntent(mSelection.canSelectMultiple(),
                mSelection.getSelectedItems()));
        finish();
    }

    private void setCancelledResultAndFinishSelf() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    /**
     * Updates the common views such as Title, Toolbar, Navigation bar, status bar and bottom sheet
     * behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     * @param title the title to set for the Activity
     */
    public void updateCommonLayouts(LayoutModeUtils.Mode mode, String title) {
        updateTitle(title);
        updateToolbar(mode);
        updateStatusBarAndNavigationBar(mode);
        updateBottomSheetBehavior(mode);
        updateFragmentContainerViewPadding(mode);
        updateDragBarVisibility(mode);
        updatePrivacyTextVisibility(mode);
    }

    private void updateTitle(String title) {
        setTitle(title);
    }

    /**
     * Updates the icons and show/hide the tab chips with {@code shouldShowTabChips}.
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateToolbar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final boolean shouldShowTabChips = mode.isPhotosTabOrAlbumsTab;
        // 1. Set the tabChip visibility
        mTabChipContainer.setVisibility(shouldShowTabChips ? View.VISIBLE : View.GONE);

        // 2. Set the toolbar color
        final ColorDrawable toolbarColor;
        if (isPreview && !shouldShowTabChips) {
            if (isOrientationLandscape()) {
                // Toolbar in Preview will have transparent color in Landscape mode.
                toolbarColor = new ColorDrawable(getColor(android.R.color.transparent));
            } else {
                // Toolbar in Preview will have a solid color with 90% opacity in Portrait mode.
                toolbarColor = new ColorDrawable(getColor(R.color.preview_scrim_solid_color));
            }
        } else {
            toolbarColor = new ColorDrawable(mDefaultBackgroundColor);
        }
        getSupportActionBar().setBackgroundDrawable(toolbarColor);

        // 3. Set the toolbar icon.
        final Drawable icon;
        if (shouldShowTabChips) {
            icon = getDrawable(R.drawable.ic_close);
        } else {
            icon = getDrawable(R.drawable.ic_arrow_back);
            // Preview mode has dark background, hence icons will be WHITE in color
            icon.setTint(isPreview ? Color.WHITE : mToolBarIconColor);
        }
        getSupportActionBar().setHomeAsUpIndicator(icon);
    }

    /**
     * Updates status bar and navigation bar
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateStatusBarAndNavigationBar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final int navigationBarColor = isPreview ? getColor(R.color.preview_background_color) :
                mDefaultBackgroundColor;
        getWindow().setNavigationBarColor(navigationBarColor);

        final int statusBarColor = isPreview ? getColor(R.color.preview_background_color) :
                getColor(android.R.color.transparent);
        getWindow().setStatusBarColor(statusBarColor);

        // Update the system bar appearance
        final int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        int appearance = 0;
        if (!isPreview) {
            final int uiModeNight =
                    getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

            if (uiModeNight == Configuration.UI_MODE_NIGHT_NO) {
                // If the system is not in Dark theme, set the system bars to light mode.
                appearance = mask;
            }
        }
        getWindow().getInsetsController().setSystemBarsAppearance(appearance, mask);
    }

    /**
     * Updates the bottom sheet behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateBottomSheetBehavior(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        if (mBottomSheetView != null) {
            mBottomSheetView.setClipToOutline(!isPreview);
            // TODO(b/197241815): Add animation downward swipe for preview should go back to
            // the photo in photos grid
            mBottomSheetBehavior.setDraggable(!isPreview);
        }
        if (isPreview) {
            if (mBottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                // Sets bottom sheet behavior state to STATE_EXPANDED if it's not already expanded.
                // This is useful when user goes to Preview mode which is always Full screen.
                // TODO(b/197241815): Add animation preview to full screen and back transition to
                // partial screen. This is similar to long press animation.
                mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        } else {
            restoreBottomSheetState();
        }
    }

    /**
     * Updates the FragmentContainerView padding.
     * <p>
     * For Preview mode, toolbar overlaps the Fragment content, hence the padding will be set to 0.
     * For Non-Preview mode, toolbar doesn't overlap the contents of the fragment, hence we set the
     * padding as the height of the toolbar.
     */
    private void updateFragmentContainerViewPadding(@NonNull LayoutModeUtils.Mode mode) {
        if (mFragmentContainerView == null) return;

        final int topPadding;
        if (mode.isPreview) {
            topPadding = 0;
        } else {
            topPadding = mToolbarHeight;
        }

        mFragmentContainerView.setPadding(mFragmentContainerView.getPaddingLeft(),
                topPadding, mFragmentContainerView.getPaddingRight(),
                mFragmentContainerView.getPaddingBottom());
    }

    private void updateDragBarVisibility(@NonNull LayoutModeUtils.Mode mode) {
        final boolean shouldShowDragBar = !mode.isPreview;
        mDragBar.setVisibility(shouldShowDragBar ? View.VISIBLE : View.GONE);
    }

    private void updatePrivacyTextVisibility(@NonNull LayoutModeUtils.Mode mode) {
        // The privacy text is only shown on the Photos tab and Albums tab
        final boolean shouldShowPrivacyMessage = mode.isPhotosTabOrAlbumsTab;
        mPrivacyText.setVisibility(shouldShowPrivacyMessage ? View.VISIBLE : View.GONE);
    }
}

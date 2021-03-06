package com.fsck.k9.activity.misc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.util.LruCache;
import android.widget.QuickContactBadge;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.Address;

public class ContactPictureLoader {
    /**
     * Resize the pictures to the following value (device-independent pixels).
     */
    private static final int PICTURE_SIZE = 40;

    private ContentResolver mContentResolver;
    private Resources mResources;
    private Contacts mContactsHelper;
    private int mPictureSizeInPx;

    /**
     * LRU cache of contact pictures.
     */
    private final LruCache<String, Bitmap> mBitmapCache;

    /**
     * @see <a href="http://developer.android.com/design/style/color.html">Color palette used</a>
     */
    private final static int CONTACT_DUMMY_COLORS_ARGB[] = {
        0xff33B5E5,
        0xffAA66CC,
        0xff99CC00,
        0xffFFBB33,
        0xffFF4444,
        0xff0099CC,
        0xff9933CC,
        0xff669900,
        0xffFF8800,
        0xffCC0000
    };

    public ContactPictureLoader(Context context, int defaultPictureResource) {
        Context appContext = context.getApplicationContext();
        mContentResolver = appContext.getContentResolver();
        mResources = appContext.getResources();
        mContactsHelper = Contacts.getInstance(appContext);

        float scale = mResources.getDisplayMetrics().density;
        mPictureSizeInPx = (int) (PICTURE_SIZE * scale);

        ActivityManager activityManager =
                (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        int memClass = activityManager.getMemoryClass();

        // Use 1/16th of the available memory for this memory cache.
        final int cacheSize = 1024 * 1024 * memClass / 16;

        mBitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in bytes rather than number of items.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
                    return bitmap.getByteCount();
                }

                return bitmap.getRowBytes() * bitmap.getHeight();
            }
        };
    }

    /**
     * Load a contact picture and display it using the supplied {@link QuickContactBadge} instance.
     *
     * <p>
     * If the supplied email address doesn't belong to any of our contacts, the default picture is
     * returned. If the picture is found in the cache, it is displayed in the
     * {@code QuickContactBadge} immediately. Otherwise a {@link ContactPictureRetrievalTask} is
     * started to try to load the contact picture in a background thread. The picture is then
     * stored in the bitmap cache or the email address is stored in the "unknown contacts cache" if
     * it doesn't belong to one of our contacts.
     * </p>
     *
     * @param email
     *         The email address that is used to search the contacts database.
     * @param badge
     *         The {@code QuickContactBadge} instance to receive the picture.
     *
     * @see #mBitmapCache
     * @see #mUnknownContactsCache
     */
    public void loadContactPicture(Address address, QuickContactBadge badge) {
    	String email = address.getAddress();
        Bitmap bitmap = getBitmapFromCache(email);
        if (bitmap != null) {
            // The picture was found in the bitmap cache
            badge.setImageBitmap(bitmap);
        } else if (cancelPotentialWork(email, badge)) {
            // Query the contacts database in a background thread and try to load the contact
            // picture, if there is one.
            ContactPictureRetrievalTask task = new ContactPictureRetrievalTask(badge);
            AsyncDrawable asyncDrawable = new AsyncDrawable(mResources, calculateFallbackBitmap(address), task);
            badge.setImageDrawable(asyncDrawable);
            try {
                task.exec(address.getAddress(), address.getPersonal());
            } catch (RejectedExecutionException e) {
                // We flooded the thread pool queue... fall back to using the default picture
                badge.setImageBitmap(calculateFallbackBitmap(address));
            }
        }
    }
    
    private int calcUnknownContactColor(Address address) {
        int val = address.getAddress().toLowerCase().hashCode();
        int rgb = CONTACT_DUMMY_COLORS_ARGB[Math.abs(val) % CONTACT_DUMMY_COLORS_ARGB.length];
    	return rgb;
    }
    
    private char calcUnknownContactLetter(Address address) {
    	String letter = "";
    	Pattern p = Pattern.compile("[^a-zA-Z]*([a-zA-Z]).*");
    	String str = address.getPersonal() != null ? address.getPersonal() : address.getAddress();
    	Matcher m = p.matcher(str);
    	if (m.matches()) {
    		letter = m.group(1).toUpperCase();
    	}
    	
        return letter.length() == 0 ? '?' : letter.charAt(0);
    }
    
    /** Calculates a bitmap with a color and a capital letter for
     * contacts without picture.
     * */
    private Bitmap calculateFallbackBitmap(Address address) {
    	Bitmap result = Bitmap.createBitmap(mPictureSizeInPx, mPictureSizeInPx, Bitmap.Config.ARGB_8888);
    	
    	Canvas canvas = new Canvas(result);
    	
    	int rgb = calcUnknownContactColor(address);
    	result.eraseColor(rgb);
    	
    	String letter = Character.toString(calcUnknownContactLetter(address));
    	
    	Paint paint = new Paint();
    	paint.setAntiAlias(true);
    	paint.setStyle(Paint.Style.FILL);
    	paint.setARGB(255, 255, 255, 255);
        paint.setTextSize(mPictureSizeInPx * 3 / 4); // just scale this down a bit
    	Rect rect = new Rect();
    	paint.getTextBounds(letter, 0, 1, rect);
    	float width = paint.measureText(letter);
    	canvas.drawText(letter, 
    			mPictureSizeInPx/2f-width/2f, 
    			mPictureSizeInPx/2f+rect.height()/2f, paint);
    	
    	return result;
    }

    private void addBitmapToCache(String key, Bitmap bitmap) {
        if (getBitmapFromCache(key) == null) {
            mBitmapCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromCache(String key) {
        return mBitmapCache.get(key);
    }

    /**
     * Checks if a {@code ContactPictureRetrievalTask} was already created to load the contact
     * picture for the supplied email address.
     *
     * @param email
     *         The email address to check the contacts database for.
     * @param badge
     *         The {@code QuickContactBadge} instance that will receive the picture.
     *
     * @return {@code true}, if the contact picture should be loaded in a background thread.
     *         {@code false}, if another {@link ContactPictureRetrievalTask} was already scheduled
     *         to load that contact picture.
     */
    private boolean cancelPotentialWork(String email, QuickContactBadge badge) {
        final ContactPictureRetrievalTask task = getContactPictureRetrievalTask(badge);

        if (task != null && email != null) {
            String emailFromTask = task.getAddress().getAddress();
            if (!email.equals(emailFromTask)) {
                // Cancel previous task
                task.cancel(true);
            } else {
                // The same work is already in progress
                return false;
            }
        }

        // No task associated with the QuickContactBadge, or an existing task was cancelled
        return true;
    }

    private ContactPictureRetrievalTask getContactPictureRetrievalTask(QuickContactBadge badge) {
        if (badge != null) {
           Drawable drawable = badge.getDrawable();
           if (drawable instanceof AsyncDrawable) {
               AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
               return asyncDrawable.getContactPictureRetrievalTask();
           }
        }

        return null;
    }


    /**
     * Load a contact picture in a background thread.
     */
    class ContactPictureRetrievalTask extends AsyncTask<String, Void, Bitmap> {
        private final WeakReference<QuickContactBadge> mQuickContactBadgeReference;
        private Address mAddress;

        ContactPictureRetrievalTask(QuickContactBadge badge) {
            mQuickContactBadgeReference = new WeakReference<QuickContactBadge>(badge);
        }

        public void exec(String... args) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, args);
            } else {
                execute(args);
            }
        }

        public Address getAddress() {
            return mAddress;
        }

        @Override
        protected Bitmap doInBackground(String... args) {
            String email = args[0];
            String personal = args[1];
            mAddress = new Address(email, personal);
            final Uri x = mContactsHelper.getPhotoUri(email);
            Bitmap bitmap = null;
            if (x != null) {
                try {
                    InputStream stream = mContentResolver.openInputStream(x);
                    if (stream != null) {
                        try {
                            Bitmap tempBitmap = BitmapFactory.decodeStream(stream);
                            if (tempBitmap != null) {
                                bitmap = Bitmap.createScaledBitmap(tempBitmap, mPictureSizeInPx,
                                        mPictureSizeInPx, true);
                                if (tempBitmap != bitmap) {
                                    tempBitmap.recycle();
                                }
                            }
                        } finally {
                            try { stream.close(); } catch (IOException e) { /* ignore */ }
                        }
                    }
                } catch (FileNotFoundException e) {
                    /* ignore */
                }

            }

            if (bitmap == null) {
            	bitmap = calculateFallbackBitmap(mAddress);
                // Remember that we don't have a contact picture for this email address
                addBitmapToCache(email, bitmap);
            } else {
                // Save the picture of the contact with that email address in the memory cache
                addBitmapToCache(email, bitmap);
            }

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (mQuickContactBadgeReference != null) {
                QuickContactBadge badge = mQuickContactBadgeReference.get();
                if (badge != null && getContactPictureRetrievalTask(badge) == this) {
                    badge.setImageBitmap(bitmap);
                }
            }
        }
    }

    /**
     * {@code Drawable} subclass that stores a reference to the {@link ContactPictureRetrievalTask}
     * that is trying to load the contact picture.
     *
     * <p>
     * The reference is used by {@link ContactPictureLoader#cancelPotentialWork(String,
     * QuickContactBadge)} to find out if the contact picture is already being loaded by a
     * {@code ContactPictureRetrievalTask}.
     * </p>
     */
    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<ContactPictureRetrievalTask> mAsyncTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, ContactPictureRetrievalTask task) {
            super(res, bitmap);
            mAsyncTaskReference = new WeakReference<ContactPictureRetrievalTask>(task);
        }

        public ContactPictureRetrievalTask getContactPictureRetrievalTask() {
            return mAsyncTaskReference.get();
        }
    }
}

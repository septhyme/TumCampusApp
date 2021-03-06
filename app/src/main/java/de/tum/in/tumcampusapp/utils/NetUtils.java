package de.tum.in.tumcampusapp.utils;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import android.widget.ImageView;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.trello.lifecycle2.android.lifecycle.AndroidLifecycle;
import com.trello.rxlifecycle2.LifecycleProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import de.tum.in.tumcampusapp.api.app.Helper;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NetUtils {
    private final Context mContext;
    private final CacheManager cacheManager;
    private final OkHttpClient client;
    private final Optional<LifecycleProvider<Lifecycle.Event>> lifecycleProvider;

    public NetUtils(Context context) {
        //Manager caches all requests
        mContext = context;
        cacheManager = new CacheManager(mContext);

        //Set our max wait time for each request
        client = Helper.getOkClient(context);

        if (context instanceof LifecycleOwner) {
            lifecycleProvider = Optional.of(AndroidLifecycle.createLifecycleProvider((LifecycleOwner) context));
        } else {
            lifecycleProvider = Optional.absent();
        }
    }

    public static Optional<JSONObject> downloadJson(Context context, String url) {
        return new NetUtils(context).downloadJson(url);
    }

    /**
     * Check if a network connection is available or can be available soon
     *
     * @return true if available
     */
    public static boolean isConnected(Context con) {
        ConnectivityManager cm = (ConnectivityManager) con
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting();
    }

    /**
     * Check if a network connection is available or can be available soon
     * and if the available connection is a mobile internet connection
     *
     * @return true if available
     */
    public static boolean isConnectedMobileData(Context con) {
        ConnectivityManager cm = (ConnectivityManager) con
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting() && netInfo.getType() == ConnectivityManager.TYPE_MOBILE;
    }

    /**
     * Check if a network connection is available or can be available soon
     * and if the available connection is a wifi internet connection
     *
     * @return true if available
     */
    public static boolean isConnectedWifi(Context con) {
        ConnectivityManager cm = (ConnectivityManager) con.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting() && netInfo.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private Optional<ResponseBody> getOkHttpResponse(String url) throws IOException {
        // if we are not online, fetch makes no sense
        boolean isOnline = isConnected(mContext);
        if (!isOnline || Strings.isNullOrEmpty(url) || url.equals("null")) {
            return Optional.absent();
        }

        Utils.logv("Download URL: " + url);
        Request.Builder builder = new Request.Builder().url(url);

        //Execute the request
        Response res = client.newCall(builder.build())
                             .execute();
        return Optional.fromNullable(res.body());

    }

    /**
     * Downloads the content of a HTTP URL as String
     *
     * @param url Download URL location
     * @return The content string
     * @throws IOException when the http call fails
     */
    public Optional<String> downloadStringHttp(String url) throws IOException {
        Optional<ResponseBody> response = getOkHttpResponse(url);
        if (response.isPresent()) {
            ResponseBody b = response.get();
            return Optional.of(b.string());

        }
        return Optional.absent();
    }

    public Optional<String> downloadStringAndCache(String url, int validity, boolean force) {
        try {
            Optional<String> content;
            if (!force) {
                content = cacheManager.getFromCache(url);
                if (content.isPresent()) {
                    return content;
                }
            }

            content = downloadStringHttp(url);
            if (content.isPresent()) {
                cacheManager.addToCache(url, content.get(), validity, CacheManager.CACHE_TYP_DATA);
                return content;
            }
            return Optional.absent();
        } catch (IOException e) {
            Utils.log(e);
            return Optional.absent();
        }

    }

    /**
     * Download a file in the same thread.
     * If file already exists the method returns immediately
     * without downloading anything
     *
     * @param url    Download location
     * @param target Target filename in local file system
     * @throws IOException When the download failed
     */
    public void downloadToFile(String url, String target) throws IOException {
        File f = new File(target);
        if (f.exists()) {
            return;
        }

        File file = new File(target);
        try (FileOutputStream out = new FileOutputStream(file)) {
            Optional<ResponseBody> body = getOkHttpResponse(url);
            if (!body.isPresent()) {
                file.delete();
                throw new IOException();
            }
            byte[] buffer = body.get()
                                .bytes();

            out.write(buffer, 0, buffer.length);
            out.flush();
        }
    }

    /**
     * Downloads an image synchronously from the given url
     *
     * @param pUrl Image url
     * @return Downloaded image as {@link Bitmap}
     */
    public Optional<File> downloadImage(String pUrl) {
        try {
            String url = pUrl.replaceAll(" ", "%20");

            Optional<String> file = cacheManager.getFromCache(url);
            if (file.isPresent()) {
                File result = new File(file.get());

                // The cache could have been cleaned manually, so we need an existence check
                return Optional.of(result);
            }

            file = Optional.of(mContext.getCacheDir()
                                       .getAbsolutePath() + '/' + Utils.hash(url) + ".jpg");
            File f = new File(file.get());
            downloadToFile(url, file.get());

            // At this point, we are certain, that the file really has been downloaded and can safely be added to the cache
            cacheManager.addToCache(url, file.get(), CacheManager.VALIDITY_TEN_DAYS, CacheManager.CACHE_TYP_IMAGE);
            return Optional.of(f);
        } catch (IOException e) {
            Utils.log(e, pUrl);
            return Optional.absent();
        } catch (IllegalArgumentException e) {
            Utils.log(e, pUrl);
            return Optional.absent();
        }
    }

    /**
     * Downloads an image synchronously from the given url
     *
     * @param url Image url
     * @return Downloaded image as {@link Bitmap}
     */
    public Optional<Bitmap> downloadImageToBitmap(@NonNull String url) {
        Optional<File> f = downloadImage(url);
        if (f.isPresent()) {
            return Optional.fromNullable(BitmapFactory.decodeFile(f.get()
                                                                   .getAbsolutePath()));
        }
        return Optional.absent();
    }

    /**
     * Download an image in background and sets the image to the image view
     *
     * @param url       URL
     * @param imageView Image
     */
    public void loadAndSetImage(final String url, final ImageView imageView) {
        synchronized (CacheManager.BITMAP_CACHE) {
            Bitmap bmp = CacheManager.BITMAP_CACHE.get(url);
            if (bmp != null) {
                imageView.setImageBitmap(bmp);
                return;
            }
        }
        CacheManager.IMAGE_VIEWS.put(imageView, url);
        imageView.setImageBitmap(null);

        Observable.just(url)
                  .map(this::downloadImageToBitmap)
                  .compose(handleLifecycle())
                  .subscribeOn(Schedulers.io())
                  .observeOn(AndroidSchedulers.mainThread())
                  .subscribe((bitmap) -> {
                      if (!bitmap.isPresent()) {
                          return;
                      }
                      synchronized (CacheManager.BITMAP_CACHE) {
                          CacheManager.BITMAP_CACHE.put(url, bitmap.get());
                      }
                      String tag = CacheManager.IMAGE_VIEWS.get(imageView);
                      if (tag != null && tag.equals(url)) {
                          imageView.setImageBitmap(bitmap.get());
                      }
                  });
    }

    private <T> ObservableTransformer<T, T> handleLifecycle() {
        if (lifecycleProvider.isPresent()) {
            return lifecycleProvider.get()
                                    .bindToLifecycle();
        }
        return observable -> observable;
    }

    /**
     * Download a JSON stream from a URL
     *
     * @param url Valid URL
     * @return JSONObject
     */
    public Optional<JSONObject> downloadJson(String url) {
        try {
            Optional<String> data = downloadStringHttp(url);
            if (data.isPresent()) {
                Utils.logv("downloadJson " + data);
                return Optional.of(new JSONObject(data.get()));
            }
        } catch (IOException | JSONException e) {
            Utils.log(e);
        }
        return Optional.absent();
    }

    /**
     * Download a JSON stream from a URL or load it from cache
     *
     * @param url   Valid URL
     * @param force Load data anyway and fill cache, even if valid cached version exists
     * @return JSONObject
     */
    public Optional<JSONArray> downloadJsonArray(String url, int validity, boolean force) {
        Optional<String> download = downloadStringAndCache(url, validity, force);
        JSONArray result = null;
        if (download.isPresent()) {
            try {
                result = new JSONArray(download.get());
            } catch (JSONException e) {
                Utils.log(e);
            }
        }
        return Optional.fromNullable(result);
    }

    /**
     * Download a JSON stream from a URL or load it from cache
     *
     * @param url   Valid URL
     * @param force Load data anyway and fill cache, even if valid cached version exists
     * @return JSONObject
     */
    public Optional<JSONObject> downloadJsonObject(String url, int validity, boolean force) {
        Optional<String> download = downloadStringAndCache(url, validity, force);
        JSONObject result = null;
        if (download.isPresent()) {
            try {
                result = new JSONObject(download.get());
            } catch (JSONException e) {
                Utils.log(e);
            }
        }
        return Optional.fromNullable(result);
    }
}

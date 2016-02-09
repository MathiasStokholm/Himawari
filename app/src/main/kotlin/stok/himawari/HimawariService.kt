package stok.himawari

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import rx.schedulers.Schedulers
import rx.Observable
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Service for downloading and displaying images captured by the Himawari satellite as a live wallpaper
 */
class HimawariService : WallpaperService() {

    override fun onCreateEngine(): Engine? {
        return HimawariEngine()
    }

    data class UrlPart(val url: String, val x: Int, val y: Int)
    data class BitmapPart(val bitmap: Bitmap, val x: Int, val y: Int)

    inner class HimawariEngine : WallpaperService.Engine() {
        val WIDTH = 550 // Width of each image obtained from endpoint
        val METRICS = let {
            val metrics = DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            metrics // Size of display
        }

        // Shared preferences and listener
        val mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_KEY, MODE_PRIVATE)
        val mPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                getString(R.string.wifi_only_key) -> mWifiOnly = sharedPreferences.getBoolean(key, false)
                getString(R.string.update_period_key) -> mPeriodMillis = sharedPreferences.getString(key, "10").toInt() * 60 * 1000L
                getString(R.string.zoom_level_key) -> updateZoom(sharedPreferences.getString(key, "1.0").toDouble())
            }
            mHandler.post { update() }
        }

        val mHandler = Handler()
        var img: Bitmap? = null
        var mVisible = false
        var mWifiOnly = true
        var mOffset = 0f
        var mZoom = 1.0
        var mLevels = updateZoom(mZoom)      // Number of images to stitch together in either direction
        var mPeriodMillis = 10 * 60 * 1000L  // Satellite updates once every 10 minutes (default value)

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            mSharedPreferences.registerOnSharedPreferenceChangeListener(mPreferenceChangeListener)
            mHandler.post { update() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            mSharedPreferences.unregisterOnSharedPreferenceChangeListener(mPreferenceChangeListener)
            mHandler.removeCallbacks(null)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            mVisible = visible
            when (visible) {
                true -> mHandler.post { draw() }
                false -> mHandler.removeCallbacks(null)
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)

            // User has scrolled. Apply a small offset and redraw image
            mOffset = -xOffset * (METRICS.widthPixels * mZoom).toInt() / 4
            if (mVisible)
                mHandler.post { draw() }
        }

        /**
         * Call to draw current image onto surface if valid
         */
        fun draw() {
            if (img != null && surfaceHolder.surface.isValid) {
                val canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(img, mOffset + (METRICS.widthPixels - img!!.width) / 2f,
                            (METRICS.heightPixels - img!!.height) / 2f, null)
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }
        }

        /**
         * Downloads and decodes a PNG image into a Bitmap on an IO thread
         */
        fun getBitmap(part: UrlPart): Observable<BitmapPart> {
            return Observable.just(URL(part.url))
                .subscribeOn(Schedulers.io())
                .map {
                    val bytes = it.readBytes()
                    BitmapPart(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), part.x, part.y)
                }
        }

        /**
         * Obtains the entire set of images that make up the most recent capture by the Himawari satellite,
         * and stitches the images together into a single Bitmap. The resulting Bitmap is scaled according
         * to the user's screen.
         * This function will schedule itself to be run every PERIOD_MILLIS (10 minutes) in order to fetch
         * and display a more recent images
         */
        fun update() {
            // Check if device is connected to wifi or allowed to use cellular network
            if (isOnWifi() || !mWifiOnly) {

                // Create a full size Bitmap to contain the final image
                val bitmap = Bitmap.createBitmap(WIDTH * mLevels, WIDTH * mLevels, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)

                // Request information about the latest capture (date and filename)
                Observable.just("http://himawari8-dl.nict.go.jp/himawari8/img/D531106/latest.json")
                        .subscribeOn(Schedulers.io())
                        .map { URL(it).readText() }
                        .flatMapIterable { response ->
                            // Extract date and time string from json response
                            val calendar = Calendar.getInstance()
                            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            calendar.time = formatter.parse(response.replace("{\"date\":\"", "").split("\"")[0])

                            // Extract correctly formatted values
                            val year = calendar.get(Calendar.YEAR)
                            val month = String.format("%02d", calendar.get(Calendar.MONTH) + 1)
                            val day = String.format("%02d", calendar.get(Calendar.DATE))
                            val hours = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY))
                            val minutes = String.format("%02d", calendar.get(Calendar.MINUTE))
                            val seconds = String.format("%02d", calendar.get(Calendar.SECOND))

                            // Create list of parts for each tile in final image
                            (0..(mLevels - 1)).flatMap { y -> (0..(mLevels - 1)).map { x ->
                                UrlPart("http://himawari8-dl.nict.go.jp/himawari8/img/D531106/${mLevels}d/$WIDTH/$year/$month/$day/$hours$minutes${seconds}_${x}_$y.png", x, y)
                            }}}
                        .flatMap { getBitmap(it) }  // Download parts of image in parallel
                        .observeOn(Schedulers.from(Executors.newSingleThreadExecutor()))
                        .map { part ->
                            // Draw into canvas using a single thread to avoid race conditions
                            canvas.drawBitmap(part.bitmap, WIDTH * part.x.toFloat(), WIDTH * part.y.toFloat(), null)
                        }.toList().subscribe({
                            // Scale bitmap to appropriate display size and redraw
                            val size = (METRICS.widthPixels * mZoom).toInt()
                            img = Bitmap.createScaledBitmap(bitmap, size, size, true)
                            mHandler.post { draw() }
                        }, { err ->
                            Log.e(HimawariService::class.java.simpleName, "Error fetching new image: ${err.message}")
                        })
            }

            // Clear all currently processing events and reschedule update
            mHandler.removeCallbacks(null)
            mHandler.postDelayed({ update() }, mPeriodMillis)
        }

        /**
         * Returns whether the device is currently connected to a Wi-Fi network
         */
        fun isOnWifi(): Boolean {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            return networkInfo.isConnected
        }

        /**
         * Updates the current zoom level and calculates a new number of images to acquire
         * returns the number of images as a convenience
         */
        fun updateZoom(newZoom: Double): Int {
            if (newZoom in 0.1 .. 10.0) {
                mZoom = newZoom
                mLevels = Math.ceil(METRICS.widthPixels * mZoom / WIDTH).toInt()
            }
            return mLevels
        }
    }
}

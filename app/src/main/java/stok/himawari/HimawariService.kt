package stok.himawari

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import rx.lang.kotlin.toSingletonObservable
import rx.schedulers.Schedulers
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

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
        val LEVELS = Math.ceil(METRICS.widthPixels / WIDTH.toDouble()).toInt()  // Number of images to stitch together in either direction
        val PERIOD_MILLIS = 10 * 60 * 1000L  // Satellite updates once every 10 minutes

        private var img: Bitmap? = null
        private var mVisible = false
        private var mOffset = 0f
        private val mHandler = Handler()

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            mHandler.post { update() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
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
            mOffset = -xOffset * METRICS.widthPixels / 4
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
                    canvas.drawBitmap(img, mOffset, (METRICS.heightPixels - img!!.height) / 2f, null)
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }
        }

        /**
         * Downloads and decodes a PNG image into a Bitmap on an IO thread
         */
        fun getBitmap(part: UrlPart): rx.Observable<BitmapPart> {
            return rx.Observable.just(URL(part.url))
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
            // Create a full size Bitmap to contain the final image
            val bitmap = Bitmap.createBitmap(WIDTH * LEVELS, WIDTH * LEVELS, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)

            rx.Observable.just("http://himawari8-dl.nict.go.jp/himawari8/img/D531106/latest.json")
                    .subscribeOn(Schedulers.io())
                    .map { URL(it).readText() }
                    .flatMapIterable { response ->
                        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        val calendar = Calendar.getInstance()
                        calendar.time = formatter.parse(response.replace("{\"date\":\"", "").split("\"")[0])

                        // Extract correctly formatted values
                        val year = calendar.get(Calendar.YEAR)
                        val month = String.format("%02d", calendar.get(Calendar.MONTH) + 1)
                        val day = String.format("%02d", calendar.get(Calendar.DATE))
                        val hours = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY))
                        val minutes = String.format("%02d", calendar.get(Calendar.MINUTE))
                        val seconds = String.format("%02d", calendar.get(Calendar.SECOND))

                        // Create list of parts for each tile in final image
                        (0..(LEVELS - 1)).flatMap { y -> (0..(LEVELS - 1)).map { x ->
                            UrlPart("http://himawari8-dl.nict.go.jp/himawari8/img/D531106/${LEVELS}d/$WIDTH/$year/$month/$day/$hours$minutes${seconds}_${x}_$y.png", x, y)
                        }}}
                    .flatMap { getBitmap(it) }
                    .observeOn(Schedulers.from(Executors.newSingleThreadExecutor()))
                    .map { part ->
                        // Draw into canvas using a single thread to avoid race conditions
                        canvas.drawBitmap(part.bitmap, WIDTH * part.x.toFloat(), WIDTH * part.y.toFloat(), null)
                    }.toList().subscribe {
                        // Scale bitmap to appropriate display size
                        img = Bitmap.createScaledBitmap(bitmap, METRICS.widthPixels, METRICS.widthPixels, true)
                        mHandler.post { draw() }
                    }

            // Reschedule update
            mHandler.postDelayed({ update() }, PERIOD_MILLIS)
        }
    }
}

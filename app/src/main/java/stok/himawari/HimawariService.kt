package stok.himawari

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import android.view.WindowManager
import rx.lang.kotlin.toSingletonObservable
import rx.schedulers.Schedulers
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class HimawariService : WallpaperService() {

    override fun onCreateEngine(): Engine? {
        return HimawariEngine()
    }

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
         * Downloads and decodes a PNG image into a Bitmap
         */
        fun getBitmap(url: String): Bitmap? {
            val png = URL(url).readBytes()
            return BitmapFactory.decodeByteArray(png, 0, png.size)
        }

        /**
         * Queries the json endpoint of the Himawari satellite to obtain the date of the most recent image,
         * and then uses this date to construct a set of URLs that may be used to obtain a set of images that
         * together make up a high-res image
         */
        fun getPaths(level: Int): rx.Observable<List<String>> {
            return "http://himawari8-dl.nict.go.jp/himawari8/img/D531106/latest.json".toSingletonObservable()
                    .subscribeOn(Schedulers.io())
                    .map { URL(it).readText() }
                    .map { response ->
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

                        // Create list of urls for each part of final image
                        (0..(level - 1)).flatMap { y -> (0..(level - 1)).map { x ->
                            "http://himawari8-dl.nict.go.jp/himawari8/img/D531106/${level}d/$WIDTH/$year/$month/$day/$hours$minutes${seconds}_${x}_$y.png" }
                        }}
        }

        /**
         * Obtains the entire set of images that make up the most recent capture by the Himawari satellite,
         * and stitches the images together into a single Bitmap. The resulting Bitmap is scaled according
         * to the user's screen.
         * This function will schedule itself to be run every PERIOD_MILLIS (10 minutes) in order to fetch
         * and display a more recent images
         */
        fun update() {
            getPaths(LEVELS)
                    .flatMapIterable { it }
                    .buffer(LEVELS)
                    .map {
                        // Stitch images horizontally
                        val bitmap = Bitmap.createBitmap(WIDTH * LEVELS, WIDTH, Bitmap.Config.RGB_565)
                        val canvas = Canvas(bitmap)
                        it.map { getBitmap(it) }.forEachIndexed { i, bitmap -> canvas.drawBitmap(bitmap, WIDTH * i.toFloat(), 0f, null) }
                        bitmap
                    }.toList()
                    .map {
                        // Stitch horizontal images vertically
                        val bitmap = Bitmap.createBitmap(WIDTH * LEVELS, WIDTH * LEVELS, Bitmap.Config.RGB_565)
                        val canvas = Canvas(bitmap)
                        it.forEachIndexed { i, bitmap -> canvas.drawBitmap(bitmap, 0f, WIDTH * i.toFloat(), null) }
                        bitmap
                    }
                    .subscribe { bitmap ->
                        // Scale bitmap to appropriate display size
                        img = Bitmap.createScaledBitmap(bitmap, METRICS.widthPixels, METRICS.widthPixels, true)
                        mHandler.post { draw() }
                    }

            // Reschedule update
            mHandler.postDelayed({ update() }, PERIOD_MILLIS)
        }
    }
}

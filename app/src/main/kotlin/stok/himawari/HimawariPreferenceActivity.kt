package stok.himawari

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.preference.PreferenceFragmentCompat

val SHARED_PREFERENCES_KEY = "shared_prefs_himawari"

class HimawariPreferenceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preferences)
    }

    class PreferenceFragment() : PreferenceFragmentCompat() {
        override fun onCreatePreferences(icicle: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesMode = MODE_PRIVATE
            preferenceManager.sharedPreferencesName = SHARED_PREFERENCES_KEY
            addPreferencesFromResource(R.xml.preferences)
        }
    }
}
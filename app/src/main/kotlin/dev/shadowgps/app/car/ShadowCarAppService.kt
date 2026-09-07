package dev.shadowgps.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The way in from Android Auto.
 *
 * The car host binds to this, asks for a session, and the session hands back the one screen
 * this app has. Everything the screen draws comes from
 * [dev.shadowgps.app.nav.NavigationHub], which the phone side keeps up to date — the car app
 * gets a read-only view of a trip rather than a second copy of the navigation logic.
 */
class ShadowCarAppService : CarAppService() {

    /**
     * Which hosts may drive this app.
     *
     * A debug build trusts anything, because that is the only way a sideloaded APK can be
     * opened by Android Auto's own developer mode — which is how this gets tested at all. A
     * release build trusts only the hosts the library itself vouches for, since a permissive
     * validator in a shipped app is an invitation to anything on the device that fancies
     * driving the navigation screen.
     *
     * That release branch leans on a resource the library marks private, which lint rightly
     * grumbles about: it could vanish in a future version. It is the allowlist Google's own
     * sample uses and there is no public equivalent, so the honest options are this or
     * hard-coding host signature digests that would rot just as fast. Worth revisiting if
     * this is ever actually shipped, which would need a driver-distraction review anyway.
     */
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = ShadowSession()
}

/** One connection to the car. Lives as long as the head unit is showing this app. */
class ShadowSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarNavigationScreen(carContext)
}

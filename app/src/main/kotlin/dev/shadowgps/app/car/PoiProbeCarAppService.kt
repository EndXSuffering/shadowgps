package dev.shadowgps.app.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

/**
 * A second way in from Android Auto, declared as a point-of-interest app.
 *
 * This is a diagnostic, not a feature, and it is meant to be deleted.
 *
 * Android Auto cannot see ShadowGPS at all: with unknown sources on and developer mode
 * enabled, it is absent from Customize launcher entirely, where Waze and Maps both appear.
 * The merged manifest is correct — CI prints it, and the navigation service, its action, its
 * category and both meta-data entries are all present and exported — so the app is being
 * packaged properly and something on the host's side is declining to list it. Reading more
 * of this project cannot distinguish between the two explanations that remain:
 *
 *  - the host never scans the app at all, in which case nothing declared here will show up;
 *  - the host scans it and declines it *because* it asks to be a navigation app, which is
 *    the category with the tightest rules about where an app came from.
 *
 * So this registers the same app a second time under the point-of-interest category, which
 * carries no such rules. One entry in the launcher, or none, is the answer — and the two are
 * told apart by what appears on screen, since this one deliberately draws a plain message
 * and nothing else.
 *
 * It shares the navigation service's host validator rather than carrying its own, so a
 * throwaway probe cannot end up trusting more than the real thing does.
 */
class PoiProbeCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator = carHostValidator(this)

    override fun onCreateSession(): Session = PoiProbeSession()
}

class PoiProbeSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = PoiProbeScreen(carContext)
}

/**
 * Says which of the two services the car found.
 *
 * A [MessageTemplate] on purpose: the navigation template this app really wants is reserved
 * for apps in the navigation category, so a probe outside that category has to draw
 * something every category is allowed to draw. That it looks nothing like the real car
 * screen is the point — there is then no way to mistake one for the other.
 */
class PoiProbeScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(
        "This is the POI probe, not the navigation service. The car can see ShadowGPS, but " +
            "is not listing it as a navigation app. Nothing here drives — tell Claude you " +
            "got this screen.",
    )
        .setTitle("ShadowGPS — POI probe")
        .build()
}

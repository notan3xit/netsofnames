import play.api._
import models.CompleteGraph
import controllers.Application

/**
 * Instance of GlobalSettings that is used to configure Play application behaviour.
 */
object Global extends GlobalSettings {

  override def onStart(app: Application) {
    // On startup, load the graph if in production (in development mode, the graph can be loaded using
    // developer features, to prevent re-loading after every restart if it is not required for the feature
    // under development).
    if (!controllers.Application.DevModeEnabled) {
      controllers.Application.startup(false)
    }
  }
}
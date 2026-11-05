import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let c = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        c.delegateClass = SceneDelegate.self
        return c
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        let nav = UINavigationController(rootViewController: GalleryViewController())
        nav.navigationBar.prefersLargeTitles = true
        nav.navigationBar.tintColor = Theme.text
        nav.overrideUserInterfaceStyle = .dark
        w.overrideUserInterfaceStyle = .dark
        w.rootViewController = nav
        w.tintColor = Theme.accent
        w.makeKeyAndVisible()
        window = w
    }

    /// the open artwork is saved whenever the app goes to the background
    func sceneDidEnterBackground(_ scene: UIScene) {
        guard let canvas = findCanvas() else { return }
        let task = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
        canvas.save()
        UIApplication.shared.endBackgroundTask(task)
    }

    private func findCanvas() -> CanvasViewController? {
        var vc = window?.rootViewController
        while let p = vc?.presentedViewController {
            if let c = p as? CanvasViewController { return c }
            vc = p
        }
        return nil
    }
}

using Android.App;
using Android.Content;
using Android.Content.PM;

namespace OpenVPNClient.Droid.TV
{
    [Activity(
        Theme = "@style/Maui.SplashTheme",
        MainLauncher = true,
        Exported = true,
        LaunchMode = LaunchMode.SingleTop,
        ConfigurationChanges = ConfigChanges.ScreenSize
                               | ConfigChanges.Orientation
                               | ConfigChanges.UiMode
                               | ConfigChanges.ScreenLayout
                               | ConfigChanges.SmallestScreenSize
                               | ConfigChanges.Density)]
    [IntentFilter(
        new[] { Intent.ActionMain },
        Categories = new[] { Intent.CategoryLeanbackLauncher })]
    public class MainActivity : MauiAppCompatActivity
    {
    }
}

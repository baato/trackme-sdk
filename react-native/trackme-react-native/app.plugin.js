// Expo config plugin: Expo regenerates ios/ and android/ (prebuild), so the native setup TrackMe needs is written
// here instead of by hand. In app.json: "plugins": [["@baato/trackme-react-native", { "locationWhenInUse": "…" }]]
//
// iOS: location usage strings, background location, Live Activities. Android needs nothing here: the TrackMe SDK's
// manifest brings its permissions and the foreground service. The Live Activity *widget extension* target is a
// separate step (see README).
const { withInfoPlist, createRunOncePlugin } = require('expo/config-plugins');
const pkg = require('./package.json');

const withTrackMe = (config, props = {}) =>
  withInfoPlist(config, (c) => {
    const plist = c.modResults;
    plist.NSLocationWhenInUseUsageDescription =
      props.locationWhenInUse ?? plist.NSLocationWhenInUseUsageDescription ?? 'Share your live location with members of your trip.';
    plist.NSLocationAlwaysAndWhenInUseUsageDescription =
      props.locationAlways ??
      plist.NSLocationAlwaysAndWhenInUseUsageDescription ??
      'Keep sharing your location with your trip while the app is in the background.';
    const modes = new Set(plist.UIBackgroundModes ?? []);
    modes.add('location');
    plist.UIBackgroundModes = [...modes];
    if (props.liveActivities !== false) plist.NSSupportsLiveActivities = true;
    return c;
  });

module.exports = createRunOncePlugin(withTrackMe, pkg.name, pkg.version);

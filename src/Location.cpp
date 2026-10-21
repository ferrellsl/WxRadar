// WxRadar (from WatchFace Viewer): where the computer is, from Windows' location service
// (Windows.Devices.Geolocation, through C++/WinRT).  Kept apart from the
// rest: the Windows Runtime's headers want their own compiler switches.

#include "Location.h"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Devices.Geolocation.h>

using namespace std::chrono_literals;

namespace location
{
  bool find(double & latitude, double & longitude, double & altitude, bool & hasAltitude)
  {
    namespace geo = winrt::Windows::Devices::Geolocation;
    bool found = false;
    try {
      winrt::init_apartment(winrt::apartment_type::multi_threaded);
      // (not allowed: location is turned off in Windows' privacy settings,
      // for the computer or for desktop apps)
      if (geo::Geolocator::RequestAccessAsync().get() == geo::GeolocationAccessStatus::Allowed) {
        geo::Geolocator locator;
        auto request = locator.GetGeopositionAsync();
        if (request.wait_for(15s) == winrt::Windows::Foundation::AsyncStatus::Completed) {
          const auto point = request.GetResults().Coordinate().Point();
          const auto position = point.Position();
          latitude = position.Latitude;
          longitude = position.Longitude;
          // (a Wi-Fi position reports a height of 0 with no reference)
          hasAltitude = point.AltitudeReferenceSystem() != geo::AltitudeReferenceSystem::Unspecified && position.Altitude != 0;
          altitude = hasAltitude ? position.Altitude : 0;
          found = true;
        }
        else
          request.Cancel();
      }
    }
    catch (winrt::hresult_error const &) {
    }
    try { winrt::uninit_apartment(); } catch (...) { }
    return found;
  }
}

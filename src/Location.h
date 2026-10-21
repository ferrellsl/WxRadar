// WatchFace Viewer: where the computer is, from Windows' location service.

#ifndef LOCATION_H
#define LOCATION_H

namespace location
{
  // The computer's latitude and longitude (degrees; north and east
  // positive).  False if Windows location is turned off or doesn't answer
  // within 15 seconds.  It waits for the answer: call it from a thread of
  // its own.
  // hasAltitude: whether Windows also gave a height above sea level
  // (meters) - it does when the position comes from GPS, not otherwise.
  bool find(double & latitude, double & longitude, double & altitude, bool & hasAltitude);
}

#endif

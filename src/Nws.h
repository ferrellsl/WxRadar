// WxRadar: the National Weather Service API (api.weather.gov) - forecasts,
// the latest observation and alerts.  Free, no key; the NWS only asks for a
// User-Agent that says who is asking (Net.cpp sends one).

#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace nws
{
  struct Period                     // one forecast period: "Tonight", or an hour
  {
    std::string name;               // "Tonight" ("" for hours)
    std::string start;              // ISO time it begins, e.g. 2026-10-21T15:00:00-07:00
    bool daytime = true;
    int temperature = 0;
    std::string unit;               // "F"
    int precipitation = -1;         // chance in percent, -1 if not given
    std::string wind;               // "5 to 10 mph SW"
    std::string shortForecast;      // "Mostly Sunny"
    std::string detailed;           // the forecaster's sentences (daily only)
  };

  struct Observation                // the latest at the nearest station; NaN = missing
  {
    std::string station;            // "KIWA"
    std::string stationName;
    std::string time;               // ISO
    std::string text;               // "Clear"
    double tempC, dewpointC, humidity, windKmh, windDegrees, gustKmh, pressurePa, visibilityM;
    Observation();
  };

  struct Ring { std::vector<double> lon, lat; };

  struct Alert
  {
    std::string id;
    std::string event;              // "Tornado Warning"
    std::string headline;
    std::string severity;           // Extreme, Severe, Moderate, Minor, Unknown
    std::string area;               // areaDesc
    std::string description, instruction;
    std::string sender;             // "NWS Phoenix AZ"
    std::string onset, expires, ends;
    std::vector<Ring> rings;        // its polygon(s), outer rings; none for zone-based alerts
    uint32_t color = 0xFFFFFF;      // the NWS's color for this kind of alert (RGB)
  };

  struct Forecast
  {
    double lat = 0, lon = 0;
    std::string place;              // "Mesa, AZ"
    std::string office;             // "PSR"
    std::vector<Period> daily, hourly;
    Observation now;
    std::vector<Alert> alerts;      // those in effect at this point
    std::string error;              // why there is none ("" if fine)
    int waiting = 0;                // parts still coming
  };

  // Starts loading everything for a point; update() is called each time a
  // part arrives (on the window's thread), with the forecast so far.  A newer
  // call makes the older one's late answers be ignored.
  void loadForecast(double lat, double lon, std::function<void(const Forecast &)> update);

  // All the alerts in effect in the US now (done(ok, alerts)).
  void loadAlerts(std::function<void(bool, std::vector<Alert> &)> done);

  // The NWS's color for a kind of alert (RGB), e.g. Tornado Warning = red.
  uint32_t alertColor(const std::string & event);
}

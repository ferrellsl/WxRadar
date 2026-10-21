// WxRadar: the National Weather Service API (see Nws.h).

#include "Nws.h"
#include "Json.h"
#include "Net.h"

#include <cmath>
#include <cstdio>
#include <memory>

namespace nws
{
  namespace
  {
    const wchar_t * const Api = L"https://api.weather.gov";

    std::wstring wide(const std::string & s)
    {
      if (s.empty()) return std::wstring();
      int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int) s.size(), nullptr, 0);
      std::wstring w(n, L'\0');
      MultiByteToWideChar(CP_UTF8, 0, s.data(), (int) s.size(), w.data(), n);
      return w;
    }

    std::wstring coords(double lat, double lon)
    {
      wchar_t text[64];
      swprintf(text, 64, L"%.4f,%.4f", lat, lon);
      return text;
    }

    double value(const Json & quantity)          // {"unitCode": ..., "value": 21.3} -> 21.3, or NaN
    {
      const Json & v = quantity["value"];
      return v.isNumber() ? v.num() : NAN;
    }

    std::vector<Period> periods(const Json & doc, size_t most)
    {
      std::vector<Period> out;
      for (const Json & p : doc["properties"]["periods"].array()) {
        if (out.size() >= most) break;
        Period q;
        q.name = p["name"].str();
        q.start = p["startTime"].str();
        q.daytime = p["isDaytime"].boolean();
        q.temperature = (int) lround(p["temperature"].num());
        q.unit = p["temperatureUnit"].str();
        const Json & pop = p["probabilityOfPrecipitation"]["value"];
        q.precipitation = pop.isNumber() ? (int) lround(pop.num()) : -1;
        q.wind = p["windSpeed"].str();
        if (!p["windDirection"].str().empty()) q.wind += " " + p["windDirection"].str();
        q.shortForecast = p["shortForecast"].str();
        q.detailed = p["detailedForecast"].str();
        out.push_back(q);
      }
      return out;
    }

    void addRing(const Json & coordinates, Alert & a)    // [[lon, lat], ...]
    {
      Ring r;
      for (const Json & pt : coordinates.array()) {
        r.lon.push_back(pt[(size_t) 0].num());
        r.lat.push_back(pt[(size_t) 1].num());
      }
      if (r.lon.size() >= 3) a.rings.push_back(std::move(r));
    }

    std::vector<Alert> alerts(const Json & doc)
    {
      std::vector<Alert> out;
      for (const Json & f : doc["features"].array()) {
        const Json & p = f["properties"];
        Alert a;
        a.id = p["id"].str();
        a.event = p["event"].str();
        a.headline = p["headline"].str();
        a.severity = p["severity"].str();
        a.area = p["areaDesc"].str();
        a.description = p["description"].str();
        a.instruction = p["instruction"].str();
        a.sender = p["senderName"].str();
        a.onset = p["onset"].str();
        a.expires = p["expires"].str();
        a.ends = p["ends"].str();
        a.color = alertColor(a.event);
        const Json & g = f["geometry"];
        const std::string & type = g["type"].str();
        if (type == "Polygon")
          addRing(g["coordinates"][(size_t) 0], a);
        else if (type == "MultiPolygon")
          for (const Json & polygon : g["coordinates"].array())
            addRing(polygon[(size_t) 0], a);
        out.push_back(std::move(a));
      }
      return out;
    }

    int generation = 0;                          // the newest loadForecast
  }

  Observation::Observation()
    : tempC(NAN), dewpointC(NAN), humidity(NAN), windKmh(NAN), windDegrees(NAN), gustKmh(NAN), pressurePa(NAN), visibilityM(NAN)
  {
  }

  void loadForecast(double lat, double lon, std::function<void(const Forecast &)> update)
  {
    const int mine = ++generation;
    auto f = std::make_shared<Forecast>();
    f->lat = lat;
    f->lon = lon;
    f->waiting = 1;
    auto stale = [mine] { return mine != generation; };

    // the point -> its forecast office and grid square, and the URLs of the rest
    net::getText(std::wstring(Api) + L"/points/" + coords(lat, lon), [=](net::Result & r) {
      if (stale()) return;
      f->waiting--;
      Json doc = Json::parse(r.body);
      const Json & p = doc["properties"];
      if (!r.ok() || !p.isObject()) {
        f->error = r.status == 404 ? "The National Weather Service forecasts only the United States and its territories."
                 : r.status == 0 ? "No answer from the National Weather Service (are you online?)."
                 : "The National Weather Service had no forecast for this point (HTTP " + std::to_string(r.status) + ").";
        update(*f);
        return;
      }
      const Json & where = p["relativeLocation"]["properties"];
      f->place = where["city"].str() + (where["state"].str().empty() ? "" : ", " + where["state"].str());
      f->office = p["gridId"].str();
      f->waiting = 4;
      update(*f);

      net::getText(wide(p["forecast"].str()), [=](net::Result & r) {
        if (stale()) return;
        f->waiting--;
        if (r.ok()) f->daily = periods(Json::parse(r.body), 14);
        else if (f->error.empty()) f->error = "The forecast didn't come (HTTP " + std::to_string(r.status) + "); it will be tried again.";
        update(*f);
      });
      net::getText(wide(p["forecastHourly"].str()), [=](net::Result & r) {
        if (stale()) return;
        f->waiting--;
        if (r.ok()) f->hourly = periods(Json::parse(r.body), 48);
        update(*f);
      });
      net::getText(std::wstring(Api) + L"/alerts/active?point=" + coords(lat, lon), [=](net::Result & r) {
        if (stale()) return;
        f->waiting--;
        if (r.ok()) f->alerts = alerts(Json::parse(r.body));
        update(*f);
      });
      // the observation: the nearest station first, then its latest report
      net::getText(wide(p["observationStations"].str()), [=](net::Result & r) {
        if (stale()) return;
        Json stations = Json::parse(r.body);
        const Json & first = stations["features"][(size_t) 0]["properties"];
        const std::string id = first["stationIdentifier"].str();
        if (!r.ok() || id.empty()) { f->waiting--; update(*f); return; }
        f->now.station = id;
        f->now.stationName = first["name"].str();
        net::getText(std::wstring(Api) + L"/stations/" + wide(id) + L"/observations/latest", [=](net::Result & r) {
          if (stale()) return;
          f->waiting--;
          Json doc = Json::parse(r.body);             // (kept: o points into it)
          const Json & o = doc["properties"];
          if (r.ok() && o.isObject()) {
            f->now.time = o["timestamp"].str();
            f->now.text = o["textDescription"].str();
            f->now.tempC = value(o["temperature"]);
            f->now.dewpointC = value(o["dewpoint"]);
            f->now.humidity = value(o["relativeHumidity"]);
            f->now.windKmh = value(o["windSpeed"]);
            f->now.windDegrees = value(o["windDirection"]);
            f->now.gustKmh = value(o["windGust"]);
            f->now.pressurePa = value(o["barometricPressure"]);
            f->now.visibilityM = value(o["visibility"]);
          }
          update(*f);
        });
      });
    });
  }

  void loadAlerts(std::function<void(bool, std::vector<Alert> &)> done)
  {
    net::getText(std::wstring(Api) + L"/alerts/active?status=actual", [done](net::Result & r) {
      std::vector<Alert> list;
      bool ok = false;
      if (r.ok()) {
        Json doc = Json::parse(r.body);
        ok = doc.isObject();
        list = alerts(doc);
      }
      done(ok, list);
    });
  }

  // The NWS's own colors for its alerts (weather.gov's map legend), the
  // common ones; anything else gets a neutral tan.
  uint32_t alertColor(const std::string & e)
  {
    struct { const char * event; uint32_t rgb; } const table[] = {
      { "Tornado Warning", 0xFF0000 },               { "Tornado Watch", 0xFFFF00 },
      { "Severe Thunderstorm Warning", 0xFFA500 },   { "Severe Thunderstorm Watch", 0xDB7093 },
      { "Flash Flood Warning", 0x8B0000 },           { "Flash Flood Watch", 0x2E8B57 },
      { "Flood Warning", 0x00FF00 },                 { "Flood Watch", 0x2E8B57 },
      { "Flood Advisory", 0x00FF7F },                { "Special Marine Warning", 0xFFA500 },
      { "Hurricane Warning", 0xDC143C },             { "Hurricane Watch", 0xFF00FF },
      { "Tropical Storm Warning", 0xB22222 },        { "Tropical Storm Watch", 0xF08080 },
      { "Storm Surge Warning", 0xB524F7 },           { "Extreme Wind Warning", 0xFF8C00 },
      { "Blizzard Warning", 0xFF4500 },              { "Winter Storm Warning", 0xFF69B4 },
      { "Winter Storm Watch", 0x4682B4 },            { "Winter Weather Advisory", 0x7B68EE },
      { "Ice Storm Warning", 0x8B008B },             { "High Wind Warning", 0xDAA520 },
      { "Wind Advisory", 0xD2B48C },                 { "Excessive Heat Warning", 0xC71585 },
      { "Heat Advisory", 0xFF7F50 },                 { "Dense Fog Advisory", 0x708090 },
      { "Red Flag Warning", 0xFF1493 },              { "Fire Weather Watch", 0xFFDEAD },
      { "Dust Storm Warning", 0xFFE4C4 },            { "Special Weather Statement", 0xFFE4B5 },
      { "Gale Warning", 0xDDA0DD },                  { "Storm Warning", 0x9400D3 },
      { "Small Craft Advisory", 0xD8BFD8 },          { "Coastal Flood Warning", 0x228B22 },
      { "Coastal Flood Advisory", 0x7CFC00 },        { "High Surf Advisory", 0xBA55D3 },
      { "Rip Current Statement", 0x40E0D0 },         { "Freeze Warning", 0x483D8B },
      { "Frost Advisory", 0x6495ED },
    };
    for (const auto & t : table)
      if (e == t.event) return t.rgb;
    return 0xD2B48C;
  }
}

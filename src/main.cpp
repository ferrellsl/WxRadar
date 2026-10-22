// WxRadar: a weather radar map for Windows - the NOAA radar loop over an
// OpenStreetMap map, the National Weather Service's warnings and forecasts,
// and no ads.
//
// Data (all free, no keys):
//   map tiles   OpenStreetMap (tile.openstreetmap.org), cached on disk for a
//               week as its tile usage policy asks
//   radar       NOAA NEXRAD composite reflectivity (N0Q), as map tiles from
//               the Iowa Environmental Mesonet: the latest picture and the
//               nine before it, 5 minutes apart
//   clouds      NOAA GOES East/West longwave infrared, from NOAA's nowCOAST
//               map service (WMS, 5-minute pictures), made into see-through
//               white clouds (Net.cpp) and matched to the radar's times
//   forecasts,  the National Weather Service API, api.weather.gov (Nws.cpp)
//   alerts
//   place names OpenStreetMap's Nominatim, for the search box
//
// The map: Web Mercator ("world" coordinates 0..256 across the whole Earth
// at zoom 0), zoom is fractional; tiles are drawn at the nearest whole zoom
// level and scaled, and while a tile loads a piece of an ancestor stands in.

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <shlobj.h>
#include <d2d1_1.h>
#include <d2d1effects.h>
#include <dwrite.h>

#include <algorithm>
#include <climits>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <functional>
#include <map>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "Json.h"
#include "Location.h"
#include "Net.h"
#include "Nws.h"

#pragma comment(lib, "d2d1.lib")
#pragma comment(lib, "dwrite.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "windowscodecs.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shell32.lib")

namespace
{
  const double Pi = 3.14159265358979323846;

  const UINT WM_NET = WM_APP + 1;          // a download is done (Net.cpp)
  const UINT WM_LOCATED = WM_APP + 2;      // Windows found where the computer is
  const UINT WM_MOTION = WM_APP + 3;       // the radar's motion has been worked out (a thread)
  const UINT_PTR TimerTick = 1;            // animation and housekeeping, about 30 a second
  const int FrameCount = 24;               // radar pictures in the loop: two hours, 5 minutes apart
  const ULONGLONG FrameMs = 125;           // each picture's time on screen while playing (8 a second, as MyRadar)...
  const ULONGLONG FadeMs = 90;             // ...the radar's last part of it fading into the next
  const ULONGLONG LastFrameMs = 1500;      // the newest one stays longer (then a cut back to the oldest)
  const int RadarMaxZoom = 8;              // radar tiles: no finer than the data (about one 0.5 km cell a pixel)
  const int SatMaxZoom = 6;                // satellite (2 km pictures): no finer than this
  const int MapMaxZoom = 18;
  const int MaxTiles = 1800;               // bitmaps kept (two hours of radar and clouds, and the map)

  // ------------------------------------------------------------- state
  HWND window = nullptr, searchBox = nullptr;
  float ui = 1.0f;                          // DPI / 96
  ID2D1Factory1 * d2d = nullptr;            // (1.1: its targets also offer ID2D1DeviceContext)
  IDWriteFactory * dwrite = nullptr;
  ID2D1HwndRenderTarget * target = nullptr;
  ID2D1DeviceContext * context = nullptr;  // the same target, for effects (Windows 8 and later)
  ID2D1BitmapRenderTarget * weatherLayer = nullptr;   // a weather layer is drawn here first...
  ID2D1Effect * blur = nullptr;                       // ...then through this onto the map
  ID2D1SolidColorBrush * brush = nullptr;
  IDWriteTextFormat * fontSmall = nullptr, * fontText = nullptr, * fontBold = nullptr, * fontBig = nullptr, * fontHuge = nullptr;

  // the view
  double camX = 128, camY = 128;           // world coordinates of the map's middle
  double zoom = 4;
  int panelWidth = 360;                    // (times ui)

  // places
  struct Place { double lat = 0, lon = 0; std::string name; bool set = false; };
  Place home, picked;                      // picked: the point the forecast is for

  // tiles
  struct Tile
  {
    ID2D1Bitmap * bitmap = nullptr;
    bool pending = false;
    ULONGLONG failedAt = 0;
    ULONGLONG used = 0;
    unsigned request = 0;                    // the newest request for it (older answers are ignored)
    std::vector<uint8_t> strength;           // for working out the motion: radar 64 x 64, clouds 128 x 128
  };
  std::unordered_map<uint64_t, Tile> tiles;   // key: layer, z, x, y
  unsigned tileRequests = 0;

  // radar
  std::string radarValid;                  // ISO time of the newest picture
  FILETIME radarValidTime = {};
  int frame = FrameCount - 1;              // shown now: 0 oldest .. FrameCount-1 newest
  bool playing = true;
  ULONGLONG frameShownAt = 0;
  float radarOpacity = 0.75f;
  ULONGLONG radarCheckedAt = 0;
  bool showRadar = true;

  // clouds: the satellite pictures' times (nowCOAST), and the one nearest each radar frame
  bool showClouds = false;
  std::vector<std::string> satTimes;
  std::string satFrame[FrameCount];        // "" = none near enough
  ULONGLONG satCheckedAt = 0;

  // the map's own controls: the Layers box's rows and the zoom buttons (for clicks)
  struct Hit { D2D1_RECT_F r; int what; };
  std::vector<Hit> hits;
  enum { HitRadar = 1, HitClouds, HitWarnings, HitZoomIn, HitZoomOut };

  // alerts and forecast
  std::vector<nws::Alert> alerts;
  bool alertsOk = false;
  ULONGLONG alertsAt = 0;
  bool showAlerts = true;
  nws::Forecast forecast;
  ULONGLONG forecastAt = 0;
  float panelScroll = 0, panelHeight = 0;
  std::vector<std::pair<D2D1_RECT_F, size_t>> alertRows;   // where each local alert is on the panel (for clicks)

  // mouse
  bool dragging = false, dragMoved = false;
  POINT dragStart = {}, dragLast = {};
  std::wstring status;                     // a message for the status line ("" none)

  std::wstring settingsFile;
  // the map's tiles: OpenStreetMap's own server unless WxRadar.ini says
  // otherwise (MapTiles=https://.../{z}/{x}/{y}.png; MapCredit= the text for
  // the bottom line), e.g. for a tile service with a key of your own
  const wchar_t * const OsmTiles = L"https://tile.openstreetmap.org/{z}/{x}/{y}.png";
  std::wstring mapTiles = OsmTiles;
  std::wstring mapCredit = L"Map \u00A9 OpenStreetMap contributors";
  std::wstring cacheFolder;

  // ------------------------------------------------------------- helpers
  std::wstring wide(const std::string & s)
  {
    if (s.empty()) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int) s.size(), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int) s.size(), w.data(), n);
    return w;
  }

  std::string narrow(const std::wstring & w)
  {
    if (w.empty()) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int) w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int) w.size(), s.data(), n, nullptr, nullptr);
    return s;
  }

  std::wstring format(const wchar_t * pattern, ...)
  {
    wchar_t text[1024];
    va_list args;
    va_start(args, pattern);
    _vsnwprintf_s(text, _countof(text), _TRUNCATE, pattern, args);
    va_end(args);
    return text;
  }

  D2D1_COLOR_F rgb(uint32_t c, float a = 1.0f)
  {
    return D2D1::ColorF(((c >> 16) & 255) / 255.0f, ((c >> 8) & 255) / 255.0f, (c & 255) / 255.0f, a);
  }

  // Web Mercator: degrees <-> world coordinates (0..256)
  double worldX(double lon) { return (lon + 180.0) / 360.0 * 256.0; }
  double worldY(double lat)
  {
    lat = std::max(-85.05, std::min(85.05, lat));
    double s = sin(lat * Pi / 180);
    return (0.5 - log((1 + s) / (1 - s)) / (4 * Pi)) * 256.0;
  }
  double lonOf(double wx) { return wx / 256.0 * 360.0 - 180.0; }
  double latOf(double wy) { double n = Pi - 2 * Pi * wy / 256.0; return 180 / Pi * atan(sinh(n)); }

  RECT client() { RECT r; GetClientRect(window, &r); return r; }
  float mapWidth() { RECT r = client(); return std::max(0.0f, (float) r.right - panelWidth * ui); }
  float mapHeight() { RECT r = client(); return (float) r.bottom; }
  double scale() { return pow(2.0, zoom) * ui; }      // screen pixels per world unit

  D2D1_POINT_2F toScreen(double wx, double wy)
  {
    return D2D1::Point2F((float) (mapWidth() / 2 + (wx - camX) * scale()), (float) (mapHeight() / 2 + (wy - camY) * scale()));
  }
  void toWorld(float sx, float sy, double & wx, double & wy)
  {
    wx = camX + (sx - mapWidth() / 2) / scale();
    wy = camY + (sy - mapHeight() / 2) / scale();
  }

  std::wstring radarSuffix(int f)            // frame -> IEM's layer name suffix
  {
    int minutes = (FrameCount - 1 - f) * 5;
    return minutes == 0 ? L"" : format(L"-m%02dm", minutes);
  }

  // A FILETIME, shown as local clock time: "3:45 PM"
  std::wstring clockTime(FILETIME utc)
  {
    SYSTEMTIME u, local;
    FileTimeToSystemTime(&utc, &u);
    SystemTimeToTzSpecificLocalTime(nullptr, &u, &local);
    int h = local.wHour % 12;
    return format(L"%d:%02d %s", h ? h : 12, local.wMinute, local.wHour < 12 ? L"AM" : L"PM");
  }

  bool parseIso(const std::string & s, FILETIME & out)      // 2026-10-21T15:45:00Z (or with an offset)
  {
    SYSTEMTIME t = {};
    int y, mo, d, h, mi, se = 0;
    if (sscanf_s(s.c_str(), "%d-%d-%dT%d:%d:%d", &y, &mo, &d, &h, &mi, &se) < 5) return false;
    t.wYear = (WORD) y; t.wMonth = (WORD) mo; t.wDay = (WORD) d; t.wHour = (WORD) h; t.wMinute = (WORD) mi; t.wSecond = (WORD) se;
    if (!SystemTimeToFileTime(&t, &out)) return false;
    // an offset (-07:00) after the seconds: to UTC
    size_t tpos = s.find('T');
    size_t sign = s.find_first_of("+-", tpos == std::string::npos ? 0 : tpos);
    if (sign != std::string::npos && s.size() >= sign + 6) {
      int oh = atoi(s.substr(sign + 1, 2).c_str()), om = atoi(s.substr(sign + 4, 2).c_str());
      LONGLONG offset = (LONGLONG) (oh * 60 + om) * 60 * 10000000LL * (s[sign] == '-' ? -1 : 1);
      ULARGE_INTEGER v; v.LowPart = out.dwLowDateTime; v.HighPart = out.dwHighDateTime;
      v.QuadPart -= offset;
      out.dwLowDateTime = v.LowPart; out.dwHighDateTime = v.HighPart;
    }
    return true;
  }

  FILETIME addMinutes(FILETIME t, int minutes)
  {
    ULARGE_INTEGER v; v.LowPart = t.dwLowDateTime; v.HighPart = t.dwHighDateTime;
    v.QuadPart += (LONGLONG) minutes * 60 * 10000000LL;
    FILETIME r; r.dwLowDateTime = v.LowPart; r.dwHighDateTime = v.HighPart;
    return r;
  }

  // the local time written in an NWS time (its own offset): "3 PM", "Wed"
  std::wstring hourOf(const std::string & iso)
  {
    if (iso.size() < 13) return L"";
    int h = atoi(iso.substr(11, 2).c_str());
    return format(L"%d %s", h % 12 ? h % 12 : 12, h < 12 ? L"AM" : L"PM");
  }

  // the NWS's short forecasts, shortened to fit a row: "Chance Showers & T-storms"
  std::wstring brief(const std::string & text)
  {
    std::wstring w = wide(text);
    const std::pair<const wchar_t *, const wchar_t *> words[] = {
      { L"Thunderstorms", L"T-storms" }, { L" And ", L" & " },
    };
    for (const auto & r : words)
      for (size_t at = 0; (at = w.find(r.first, at)) != std::wstring::npos; at += wcslen(r.second))
        w.replace(at, wcslen(r.first), r.second);      // (going on after what was put in: never round again)
    return w;
  }

  std::wstring compass(double degrees)
  {
    static const wchar_t * const names[] = { L"N", L"NNE", L"NE", L"ENE", L"E", L"ESE", L"SE", L"SSE",
                                             L"S", L"SSW", L"SW", L"WSW", L"W", L"WNW", L"NW", L"NNW" };
    return names[(int) floor(fmod(degrees + 371.25, 360.0) / 22.5) % 16];
  }

  // ------------------------------------------------------------- settings
  void saveSettings()
  {
    auto put = [](const wchar_t * key, const std::wstring & v) { WritePrivateProfileStringW(L"WxRadar", key, v.c_str(), settingsFile.c_str()); };
    if (home.set) {
      put(L"HomeLat", format(L"%.5f", home.lat));
      put(L"HomeLon", format(L"%.5f", home.lon));
      put(L"HomeName", wide(home.name));
    }
    put(L"ViewLat", format(L"%.5f", latOf(camY)));
    put(L"ViewLon", format(L"%.5f", lonOf(camX)));
    put(L"Zoom", format(L"%.2f", zoom));
    put(L"RadarOpacity", format(L"%.2f", radarOpacity));
    put(L"ShowAlerts", showAlerts ? L"1" : L"0");
    put(L"ShowRadar", showRadar ? L"1" : L"0");
    put(L"ShowClouds", showClouds ? L"1" : L"0");
    WINDOWPLACEMENT wp = { sizeof(wp) };
    if (GetWindowPlacement(window, &wp)) {
      const RECT & r = wp.rcNormalPosition;
      put(L"Window", format(L"%d %d %d %d %d", (int) r.left, (int) r.top, (int) r.right, (int) r.bottom, wp.showCmd == SW_SHOWMAXIMIZED ? 1 : 0));
    }
  }

  std::wstring setting(const wchar_t * key, const wchar_t * otherwise = L"")
  {
    wchar_t text[512];
    GetPrivateProfileStringW(L"WxRadar", key, otherwise, text, _countof(text), settingsFile.c_str());
    return text;
  }

  // ------------------------------------------------------------- tiles
  uint64_t tileKey(int layer, int z, int x, int y)
  {
    return ((uint64_t) layer << 56) | ((uint64_t) z << 50) | ((uint64_t) x << 25) | (uint64_t) y;
  }

  // layer 0: the map; 1..FrameCount: the radar frames; then the satellite frames
  int radarLayer(int f) { return 1 + f; }
  int satLayer(int f) { return 1 + FrameCount + f; }
  bool isSatLayer(int layer) { return layer > FrameCount; }

  void dropTile(Tile & t)
  {
    if (t.bitmap) t.bitmap->Release();
    t.bitmap = nullptr;
  }

  void dropMotion();                       // (below)
  void dropMotion(int which);

  void dropRadarTiles()
  {
    dropMotion();
    for (auto it = tiles.begin(); it != tiles.end();) {
      if ((it->first >> 56) != 0) { dropTile(it->second); it = tiles.erase(it); }
      else ++it;
    }
  }

  void dropAllTiles()
  {
    for (auto & t : tiles) dropTile(t.second);
    tiles.clear();
  }

  void trimTiles()
  {
    if (tiles.size() <= (size_t) MaxTiles) return;
    std::vector<std::pair<ULONGLONG, uint64_t>> byAge;
    for (auto & t : tiles)
      if (!t.second.pending) byAge.push_back({ t.second.used, t.first });
    std::sort(byAge.begin(), byAge.end());
    size_t remove = tiles.size() - MaxTiles * 3 / 4;
    for (size_t i = 0; i < remove && i < byAge.size(); ++i) {
      dropTile(tiles[byAge[i].second]);
      tiles.erase(byAge[i].second);
    }
  }

  void requestTile(int layer, int z, int x, int y)
  {
    const uint64_t key = tileKey(layer, z, x, y);
    Tile & t = tiles[key];
    t.used = GetTickCount64();
    if (t.bitmap || t.pending) return;
    if (t.failedAt && GetTickCount64() - t.failedAt < 60000) return;   // (try a failed one again a minute later)
    t.pending = true;

    std::wstring url, cache;
    int age = 0;
    if (layer == 0) {
      url = mapTiles;
      for (const auto & part : { std::make_pair(L"{z}", z), std::make_pair(L"{x}", x), std::make_pair(L"{y}", y) }) {
        size_t at = url.find(part.first);
        if (at != std::wstring::npos) url.replace(at, 3, std::to_wstring(part.second));
      }
      // (a cache folder of its own for each tile server)
      cache = format(L"%s\\%s\\%d\\%d\\%d.png", cacheFolder.c_str(),
                     mapTiles == OsmTiles ? L"osm" : format(L"map-%08x", (unsigned) std::hash<std::wstring>()(mapTiles)).c_str(), z, x, y);
      age = 24 * 7;
    }
    else if (isSatLayer(layer)) {
      // nowCOAST's WMS: the tile's square in Web Mercator meters, at one time
      const std::string & when = satFrame[layer - 1 - FrameCount];
      if (when.empty()) { t.pending = false; t.failedAt = GetTickCount64(); return; }
      const double R = 20037508.342789244, size = 2 * R / (1 << z);
      url = format(L"https://nowcoast.noaa.gov/geoserver/satellite/wms?service=WMS&version=1.3.0&request=GetMap"
                   L"&layers=goes_longwave_imagery&styles=&format=image/png&transparent=true&crs=EPSG:3857"
                   L"&bbox=%.2f,%.2f,%.2f,%.2f&width=256&height=256&time=%s",
                   -R + x * size, R - (y + 1) * size, -R + (x + 1) * size, R - y * size, wide(when).c_str());
    }
    else {
      // the frame's own time, e.g. ridge::USCOMP-N0Q-202610211545 (with "minutes before
      // the latest" names, tiles fetched either side of a new picture wouldn't match)
      if (radarValid.empty()) { t.pending = false; return; }
      SYSTEMTIME st;
      FILETIME when = addMinutes(radarValidTime, -(FrameCount - 1 - (layer - 1)) * 5);
      FileTimeToSystemTime(&when, &st);
      url = format(L"https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/ridge::USCOMP-N0Q-%04d%02d%02d%02d%02d/%d/%d/%d.png",
                   st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, z, x, y);
    }

    // (a tile dropped and asked for again - new radar or satellite times - is a
    // new request: the old one's answer, if it comes after, is not this tile's)
    const unsigned request = t.request = ++tileRequests;
    net::getImage(url, cache, age, [key, layer, request](net::Result & r) {
      auto it = tiles.find(key);
      if (it == tiles.end() || it->second.request != request) return;   // (dropped meanwhile)
      Tile & t = it->second;
      t.pending = false;
      if (r.status == 0 && r.pixels.empty()) return;          // (dropped from the queue: asked again if still needed)
      if (!r.ok() || r.pixels.empty() || !target) { t.failedAt = GetTickCount64(); return; }
      D2D1_BITMAP_PROPERTIES props = D2D1::BitmapProperties(D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_PREMULTIPLIED));
      if (layer >= 1 && r.width == 256 && r.height == 256) {
        // what the motion is worked out from.  Radar: rain strength, roughly -
        // none where the tile is clear, more the redder it is (light rain is
        // blue-green, heavy yellow to red); 4 x 4 pixels a cell.  Clouds: how
        // solid they are; 2 x 2 pixels a cell (the satellite's pixels are coarser).
        const bool clouds = isSatLayer(layer);
        const int side = clouds ? 128 : 64, k = 256 / side;
        t.strength.assign((size_t) side * side, 0);
        for (int cy = 0; cy < side; ++cy)
          for (int cx = 0; cx < side; ++cx) {
            int sum = 0;
            for (int j = 0; j < k; ++j)
              for (int i = 0; i < k; ++i) {
                const uint8_t * q = &r.pixels[((size_t) (cy * k + j) * 256 + cx * k + i) * 4];
                sum += clouds ? q[3] : (q[3] + q[2]) / 2;          // (alpha + premultiplied red)
              }
            t.strength[(size_t) cy * side + cx] = (uint8_t) (sum / (k * k));
          }
      }
      if (SUCCEEDED(target->CreateBitmap(D2D1::SizeU(r.width, r.height), r.pixels.data(), r.width * 4, props, &t.bitmap)))
        InvalidateRect(window, nullptr, FALSE);
      else
        t.failedAt = GetTickCount64();
    }, isSatLayer(layer) ? net::Clouds : net::AsIs);
  }

  // The tiles of a layer in view at whole zoom level z: x0..x1, y0..y1
  void tileRange(int z, int & x0, int & y0, int & x1, int & y1)
  {
    double wx0, wy0, wx1, wy1;
    toWorld(0, 0, wx0, wy0);
    toWorld(mapWidth(), mapHeight(), wx1, wy1);
    const double n = pow(2.0, z), size = 256.0 / n;
    x0 = (int) floor(wx0 / size); x1 = (int) floor(wx1 / size);
    y0 = std::max(0, (int) floor(wy0 / size)); y1 = std::min((int) n - 1, (int) floor(wy1 / size));
  }

  int levelFor(int maxZoom)
  {
    // the whole zoom level whose tiles come out nearest their own size
    int z = (int) floor(zoom + log2(ui) + 0.5);
    return std::max(0, std::min(maxZoom, z));
  }

  // Ask for every tile in view: the radar frames other than the one shown
  // first (they are taken last: the newest request goes first), then the map,
  // then the frame shown.
  void requestLayer(int layer, int maxZoom)
  {
    int x0, y0, x1, y1;
    const int z = levelFor(maxZoom), n = 1 << z;
    tileRange(z, x0, y0, x1, y1);
    for (int y = y0; y <= y1; ++y)
      for (int x = x0; x <= x1; ++x)
        requestTile(layer, z, ((x % n) + n) % n, y);
  }

  void requestView()
  {
    if (!target) return;
    net::dropWaitingImages();
    for (int f = 0; f < FrameCount; ++f)
      if (f != frame) {
        if (showClouds) requestLayer(satLayer(f), SatMaxZoom);
        if (showRadar) requestLayer(radarLayer(f), RadarMaxZoom);
      }
    requestLayer(0, MapMaxZoom);
    if (showClouds) requestLayer(satLayer(frame), SatMaxZoom);
    if (showRadar) requestLayer(radarLayer(frame), RadarMaxZoom);
    trimTiles();
  }

  // Is every tile of a layer in view here (or given up on)?
  bool layerLoaded(int layer, int maxZoom)
  {
    int x0, y0, x1, y1;
    const int z = levelFor(maxZoom), n = 1 << z;
    tileRange(z, x0, y0, x1, y1);
    for (int y = y0; y <= y1; ++y)
      for (int x = x0; x <= x1; ++x) {
        auto it = tiles.find(tileKey(layer, z, ((x % n) + n) % n, y));
        if (it == tiles.end() || (!it->second.bitmap && !it->second.failedAt)) return false;
      }
    return true;
  }

  bool frameLoaded(int f)
  {
    return (!showRadar || layerLoaded(radarLayer(f), RadarMaxZoom)) &&
           (!showClouds || satFrame[f].empty() || layerLoaded(satLayer(f), SatMaxZoom));
  }

  // Draws one layer: each tile in view, or while it loads, the part of the
  // nearest ancestor that covers it (onto rt: the window, or weatherLayer).
  void drawLayer(int layer, int maxZoom, float opacity, ID2D1RenderTarget * rt = nullptr)
  {
    if (!rt) rt = target;
    const int z = levelFor(maxZoom), n = 1 << z;
    int x0, y0, x1, y1;
    tileRange(z, x0, y0, x1, y1);
    const double size = 256.0 / n;
    const ULONGLONG now = GetTickCount64();
    for (int y = y0; y <= y1; ++y)
      for (int x = x0; x <= x1; ++x) {
        const int wrapped = ((x % n) + n) % n;
        D2D1_POINT_2F a = toScreen(x * size, y * size), b = toScreen((x + 1) * size, (y + 1) * size);
        D2D1_RECT_F dest = D2D1::RectF(roundf(a.x), roundf(a.y), roundf(b.x), roundf(b.y));   // (edges meet exactly)
        for (int up = 0; up <= 6 && z - up >= 0; ++up) {
          const int pz = z - up, px = wrapped >> up, py = y >> up;
          auto it = tiles.find(tileKey(layer, pz, px, py));
          if (it == tiles.end() || !it->second.bitmap) continue;
          it->second.used = now;
          // the piece of the ancestor tile that covers (x, y)
          const float part = 256.0f / (1 << up);
          const float sx = (wrapped - (px << up)) * part, sy = (y - (py << up)) * part;
          rt->DrawBitmap(it->second.bitmap, dest, opacity, D2D1_BITMAP_INTERPOLATION_MODE_LINEAR,
                         D2D1::RectF(sx, sy, sx + part, sy + part));
          break;
        }
      }
  }

  // A weather layer (radar, clouds): picture layerA, fading into layerB by t
  // (0..1).  It is put together off screen: A at (1 - t), then B added at t,
  // so where both have rain it stays as strong all through the fade.
  // The pictures are coarse - the radar's cells are about half a kilometer,
  // a pixel of its tiles - so zoomed in, each cell would show as a square;
  // the layer goes onto the map through a Gaussian blur about a third of a
  // cell wide, and the cells melt into smooth shapes, as in the radar apps.
  // Zoomed out (cells under a couple of pixels) there is no blur.
  void drawWeather(int layerA, int layerB, float t, int maxZoom, float opacity)
  {
    const float cell = (float) pow(2.0, zoom + log2(ui) - levelFor(maxZoom));    // screen pixels a tile pixel
    const bool blurIt = cell >= 1.6f;
    const UINT32 w = (UINT32) mapWidth(), h = (UINT32) mapHeight();
    if (context && weatherLayer) {
      D2D1_SIZE_U have = weatherLayer->GetPixelSize();
      if (have.width != w || have.height != h) { weatherLayer->Release(); weatherLayer = nullptr; }
    }
    if (context && !weatherLayer && FAILED(target->CreateCompatibleRenderTarget(D2D1::SizeF((float) w, (float) h), &weatherLayer)))
      weatherLayer = nullptr;
    if (context && !blur && FAILED(context->CreateEffect(CLSID_D2D1GaussianBlur, &blur)))
      blur = nullptr;
    if (!context || !weatherLayer || (blurIt && !blur)) {         // (no Direct2D 1.1: as it is, no fade)
      drawLayer(t < 0.5f ? layerA : layerB, maxZoom, opacity);
      return;
    }

    weatherLayer->BeginDraw();
    weatherLayer->Clear(D2D1::ColorF(0, 0, 0, 0));
    drawLayer(layerA, maxZoom, 1.0f - t, weatherLayer);
    if (t > 0) {
      ID2D1DeviceContext * adding = nullptr;
      if (SUCCEEDED(weatherLayer->QueryInterface(__uuidof(ID2D1DeviceContext), (void **) &adding))) {
        adding->SetPrimitiveBlend(D2D1_PRIMITIVE_BLEND_ADD);
        drawLayer(layerB, maxZoom, t, weatherLayer);
        adding->SetPrimitiveBlend(D2D1_PRIMITIVE_BLEND_SOURCE_OVER);
        adding->Release();
      }
      else
        drawLayer(layerB, maxZoom, t, weatherLayer);
    }
    ID2D1Bitmap * picture = nullptr;
    if (FAILED(weatherLayer->EndDraw()) || FAILED(weatherLayer->GetBitmap(&picture))) {
      drawLayer(t < 0.5f ? layerA : layerB, maxZoom, opacity);
      return;
    }
    context->PushLayer(D2D1::LayerParameters1(D2D1::InfiniteRect(), nullptr, D2D1_ANTIALIAS_MODE_ALIASED,
                                              D2D1::IdentityMatrix(), opacity), nullptr);
    if (blurIt) {
      blur->SetInput(0, picture);
      blur->SetValue(D2D1_GAUSSIANBLUR_PROP_STANDARD_DEVIATION, 0.32f * cell);
      blur->SetValue(D2D1_GAUSSIANBLUR_PROP_BORDER_MODE, D2D1_BORDER_MODE_SOFT);
      blur->SetValue(D2D1_GAUSSIANBLUR_PROP_OPTIMIZATION, D2D1_GAUSSIANBLUR_OPTIMIZATION_BALANCED);
      context->DrawImage(blur);
      blur->SetInput(0, nullptr);
    }
    else
      context->DrawImage(picture);
    context->PopLayer();
    picture->Release();
  }

  // ------------------------------------------------------------- weather motion
  // Between two radar pictures 5 minutes apart, the rain is drawn where it
  // was on its way: the earlier picture slid forward along the storms'
  // motion, the later one slid back, and the two blended - true in-between
  // pictures, not a cross-fade.  The clouds move the same way, with a motion
  // of their own (they ride the winds higher up, often faster and in another
  // direction than the storms below).
  //
  // The motion: the view is cut into cells (about 2 km for the radar, 4 km
  // for the clouds, whose satellite pixels are coarser); each frame becomes
  // a grid of strength (from the tiles' strength copies); for each block of
  // 6 x 6 cells with something in it, the shift that best matches it in the
  // next frame is found (all the loop's pairs), and those shifts, weighted by
  // how much they rest on, are averaged into one motion field for the view
  // (weather drifts with the steering winds, so the loop's average is
  // steadier than any one pair).  Rain blocks are weighted by their rain;
  // cloud blocks by their edges (an even overcast has nothing to follow), and
  // their shifts are refined to a fraction of a cell.  Blocks with nothing
  // take their neighbors' motion.  It is worked out on a thread when the view
  // has been still for a moment and every frame is here, again after a pan,
  // a zoom or new pictures; until then the frames cross-fade (the clouds step).
  //
  // The drawing (Direct2D effects, on the graphics card): the field becomes
  // a small picture (red = x, green = y), scaled up to the view, and drives
  // two Displacement Map effects; an Arithmetic Composite blends them.

  struct View
  {
    double camX = 0, camY = 0, zoom = 0;
    UINT32 w = 0, h = 0;
    bool operator==(const View & o) const { return camX == o.camX && camY == o.camY && zoom == o.zoom && w == o.w && h == o.h; }
  };
  View currentView() { View v; v.camX = camX; v.camY = camY; v.zoom = zoom; v.w = (UINT32) mapWidth(); v.h = (UINT32) mapHeight(); return v; }

  enum { RadarMotion = 0, CloudMotion = 1 };

  struct MotionField
  {
    int which = RadarMotion;
    View view;
    int bw = 0, bh = 0;                    // blocks across, down
    float blockPx = 0;                     // a block's size on screen
    float vmax = 1;                        // the largest move, screen pixels per 5 minutes
    std::vector<float> vx, vy;             // per block, screen pixels per 5 minutes
  };

  struct MotionInput
  {
    int which = RadarMotion;
    int job = 0;
    View view;
    int gw = 0, gh = 0, reach = 0;         // cells across, down; the farthest move looked for, in cells
    float cellPx = 0;
    std::vector<std::vector<uint8_t>> frames;
    std::vector<char> pairs;               // pairs[f]: frames f and f + 1 can be compared
  };

  // One weather layer's motion, and what draws it moving
  struct Mover
  {
    MotionField field;                     // in use (vx empty: none)
    ID2D1Bitmap * map = nullptr;           // the field as a picture
    int job = 0;                           // the newest computation (older answers are ignored)
    bool running = false;
    ID2D1BitmapRenderTarget * layerA = nullptr, * layerB = nullptr;
    ID2D1Effect * fxScale = nullptr, * fxMoveA = nullptr, * fxMoveB = nullptr, * fxMix = nullptr, * fxBlur = nullptr;
  };
  Mover movers[2];                         // RadarMotion, CloudMotion
  int motionJobs = 0;
  View lastView;
  ULONGLONG viewSteadySince = 0;

  bool motionReady(int which)
  {
    const Mover & m = movers[which];
    return !m.field.vx.empty() && m.map && m.field.view == currentView();
  }

  void dropMotion(int which)
  {
    Mover & m = movers[which];
    m.field.vx.clear();
    m.field.vy.clear();
    if (m.map) { m.map->Release(); m.map = nullptr; }
    m.job = ++motionJobs;
    m.running = false;
  }

  void dropMotion()
  {
    dropMotion(RadarMotion);
    dropMotion(CloudMotion);
  }

  void releaseMovers()
  {
    for (Mover & m : movers) {
      for (ID2D1Effect ** fx : { &m.fxScale, &m.fxMoveA, &m.fxMoveB, &m.fxMix, &m.fxBlur })
        if (*fx) { (*fx)->Release(); *fx = nullptr; }
      if (m.layerA) { m.layerA->Release(); m.layerA = nullptr; }
      if (m.layerB) { m.layerB->Release(); m.layerB = nullptr; }
    }
    dropMotion();
  }

  // Can the clouds move from frame f to f + 1: two satellite pictures about 5 minutes apart?
  bool cloudPair(int f)
  {
    if (f < 0 || f + 1 >= FrameCount || satFrame[f].empty() || satFrame[f + 1].empty()) return false;
    FILETIME a, b;
    if (!parseIso(satFrame[f], a) || !parseIso(satFrame[f + 1], b)) return false;
    ULARGE_INTEGER ua, ub;
    ua.LowPart = a.dwLowDateTime; ua.HighPart = a.dwHighDateTime;
    ub.LowPart = b.dwLowDateTime; ub.HighPart = b.dwHighDateTime;
    const double minutes = (double) ((LONGLONG) ub.QuadPart - (LONGLONG) ua.QuadPart) / 6e8;
    return minutes > 3 && minutes < 7;
  }

  // A frame of a layer as a grid of strength (0..255: rain, or cloud), cells cellPx square.
  std::vector<uint8_t> strengthGrid(int layer, int maxZoom, float cellPx, int gw, int gh)
  {
    std::vector<uint8_t> grid((size_t) gw * gh, 0);
    const int z = levelFor(maxZoom), n = 1 << z;
    const double tileWorld = 256.0 / n;
    for (int j = 0; j < gh; ++j)
      for (int i = 0; i < gw; ++i) {
        double wx, wy;
        toWorld((i + 0.5f) * cellPx, (j + 0.5f) * cellPx, wx, wy);
        const int tx = (int) floor(wx / tileWorld), ty = (int) floor(wy / tileWorld);
        if (ty < 0 || ty >= n) continue;
        auto it = tiles.find(tileKey(layer, z, ((tx % n) + n) % n, ty));
        if (it == tiles.end() || it->second.strength.empty()) continue;
        const int side = it->second.strength.size() == 128 * 128 ? 128 : 64;
        const int px = std::min(side - 1, std::max(0, (int) ((wx / tileWorld - tx) * side)));
        const int py = std::min(side - 1, std::max(0, (int) ((wy / tileWorld - ty) * side)));
        grid[(size_t) j * gw + i] = it->second.strength[(size_t) py * side + px];
      }
    return grid;
  }

  // (on a thread: only its input)
  MotionField * workOutMotion(const MotionInput & in)
  {
    const bool clouds = in.which == CloudMotion;
    const int B = 6, S = 3, R = in.reach;
    const int bw = in.gw / S + 2, bh = in.gh / S + 2;          // (one more each way: the edges)
    std::vector<double> sx((size_t) bw * bh, 0), sy(sx.size(), 0), sw(sx.size(), 0);
    auto cell = [&](const std::vector<uint8_t> & g, int x, int y) -> int {
      return (x < 0 || y < 0 || x >= in.gw || y >= in.gh) ? 0 : g[(size_t) y * in.gw + x];
    };
    for (size_t f = 0; f + 1 < in.frames.size(); ++f) {
      if (!in.pairs[f]) continue;
      const std::vector<uint8_t> & A = in.frames[f], & Bn = in.frames[f + 1];
      for (int bj = 0; bj < bh; ++bj)
        for (int bi = 0; bi < bw; ++bi) {
          const int x0 = bi * S - 1, y0 = bj * S - 1;             // the block: 6 x 6 cells around its own 3 x 3
          int signal = 0;
          if (clouds) {
            // the clouds' edges and texture: what can be followed
            for (int j = 0; j < B; ++j)
              for (int i = 0; i < B; ++i) {
                const int c = cell(A, x0 + i, y0 + j);
                signal += abs(cell(A, x0 + i + 1, y0 + j) - c) + abs(cell(A, x0 + i, y0 + j + 1) - c);
              }
            if (signal < B * B * 12) continue;
          }
          else {
            for (int j = 0; j < B; ++j)
              for (int i = 0; i < B; ++i) signal += cell(A, x0 + i, y0 + j);
            if (signal < B * B * 40) continue;                     // (too little rain to follow)
          }
          auto sad = [&](int dx, int dy, int stop) {
            int s = 0;
            for (int j = 0; j < B && s < stop; ++j)
              for (int i = 0; i < B; ++i)
                s += abs(cell(A, x0 + i, y0 + j) - cell(Bn, x0 + i + dx, y0 + j + dy));
            return s;
          };
          int best = INT_MAX, bx = 0, by = 0;
          for (int dy = -R; dy <= R; ++dy)
            for (int dx = -R; dx <= R; ++dx) {
              const int bias = (abs(dx) + abs(dy)) * 8;            // (all else equal, the shorter move)
              const int s = bias + sad(dx, dy, best - bias);
              if (s < best) { best = s; bx = dx; by = dy; }
            }
          float fx = (float) bx, fy = (float) by;
          if (clouds) {
            // to a fraction of a cell: the bottom of a parabola through the best and its neighbors
            auto refine = [](int l, int c, int r) {
              const int d = l - 2 * c + r;
              return d > 0 ? std::max(-0.5f, std::min(0.5f, 0.5f * (l - r) / d)) : 0.0f;
            };
            const int c = sad(bx, by, INT_MAX);
            if (abs(bx) < R) fx += refine(sad(bx - 1, by, INT_MAX), c, sad(bx + 1, by, INT_MAX));
            if (abs(by) < R) fy += refine(sad(bx, by - 1, INT_MAX), c, sad(bx, by + 1, INT_MAX));
          }
          const size_t k = (size_t) bj * bw + bi;
          sx[k] += (double) signal * fx;
          sy[k] += (double) signal * fy;
          sw[k] += signal;
        }
    }
    MotionField * m = new MotionField;
    m->which = in.which;
    m->view = in.view;
    m->bw = bw;
    m->bh = bh;
    m->blockPx = S * in.cellPx;
    m->vx.assign((size_t) bw * bh, 0);
    m->vy.assign((size_t) bw * bh, 0);
    std::vector<char> known((size_t) bw * bh, 0);
    for (size_t k = 0; k < known.size(); ++k)
      if (sw[k] > 0) { m->vx[k] = (float) (sx[k] / sw[k]); m->vy[k] = (float) (sy[k] / sw[k]); known[k] = 1; }
    // blocks with nothing to follow: their neighbors' motion, spreading out
    for (int pass = 0; pass < 64; ++pass) {
      bool any = false;
      std::vector<char> now = known;
      for (int j = 0; j < bh; ++j)
        for (int i = 0; i < bw; ++i) {
          const size_t k = (size_t) j * bw + i;
          if (known[k]) continue;
          float ax = 0, ay = 0;
          int n = 0;
          for (int dj = -1; dj <= 1; ++dj)
            for (int di = -1; di <= 1; ++di) {
              int x = i + di, y = j + dj;
              if (x < 0 || y < 0 || x >= bw || y >= bh || !known[(size_t) y * bw + x]) continue;
              ax += m->vx[(size_t) y * bw + x]; ay += m->vy[(size_t) y * bw + x]; ++n;
            }
          if (n) { m->vx[k] = ax / n; m->vy[k] = ay / n; now[k] = 1; any = true; }
        }
      known.swap(now);
      if (!any) break;
    }
    // smoothed, then in screen pixels
    for (int pass = 0; pass < 3; ++pass) {
      std::vector<float> nx = m->vx, ny = m->vy;
      for (int j = 0; j < bh; ++j)
        for (int i = 0; i < bw; ++i) {
          float ax = 0, ay = 0;
          int n = 0;
          for (int dj = -1; dj <= 1; ++dj)
            for (int di = -1; di <= 1; ++di) {
              int x = i + di, y = j + dj;
              if (x < 0 || y < 0 || x >= bw || y >= bh) continue;
              ax += m->vx[(size_t) y * bw + x]; ay += m->vy[(size_t) y * bw + x]; ++n;
            }
          nx[(size_t) j * bw + i] = ax / n;
          ny[(size_t) j * bw + i] = ay / n;
        }
      m->vx.swap(nx);
      m->vy.swap(ny);
    }
    m->vmax = 1;
    for (size_t k = 0; k < m->vx.size(); ++k) {
      m->vx[k] *= in.cellPx;
      m->vy[k] *= in.cellPx;
      m->vmax = std::max(m->vmax, std::max(fabsf(m->vx[k]), fabsf(m->vy[k])));
    }
    return m;
  }

  bool radarLoaded()
  {
    for (int f = 0; f < FrameCount; ++f)
      if (!layerLoaded(radarLayer(f), RadarMaxZoom)) return false;
    return true;
  }

  bool cloudsLoaded()
  {
    for (int f = 0; f < FrameCount; ++f)
      if (!satFrame[f].empty() && !layerLoaded(satLayer(f), SatMaxZoom)) return false;
    return true;
  }

  void startMotion(int which)
  {
    // radar: cells of about 2 km (6 to 40 screen pixels), moves up to 10 km in 5 minutes (75 mph);
    // clouds: cells of about 4 km (8 to 40 pixels), moves up to 25 km in 5 minutes (the jet stream, 185 mph)
    const bool clouds = which == CloudMotion;
    const double pxPerKm = scale() / (40075.0 * cos(latOf(camY) * Pi / 180) / 256.0);
    MotionInput in;
    in.which = which;
    in.cellPx = (float) std::max(clouds ? 8.0 : 6.0, std::min(40.0, (clouds ? 4.0 : 2.0) * pxPerKm));
    in.reach = std::max(2, std::min(8, (int) ceil((clouds ? 25.0 : 10.0) / (in.cellPx / pxPerKm))));
    in.view = currentView();
    in.gw = (int) ceil(in.view.w / in.cellPx);
    in.gh = (int) ceil(in.view.h / in.cellPx);
    if (in.gw < 8 || in.gh < 8) return;                         // (zoomed in too far to tell)
    bool anyPair = false;
    for (int f = 0; f < FrameCount; ++f) {
      in.frames.push_back(clouds && satFrame[f].empty() ? std::vector<uint8_t>()
                          : strengthGrid(clouds ? satLayer(f) : radarLayer(f), clouds ? SatMaxZoom : RadarMaxZoom, in.cellPx, in.gw, in.gh));
      const bool pair = f + 1 < FrameCount && (!clouds || cloudPair(f));
      in.pairs.push_back(pair);
      anyPair |= pair;
    }
    if (!anyPair) return;
    Mover & mv = movers[which];
    in.job = mv.job = ++motionJobs;
    mv.running = true;
    HWND w = window;
    std::thread([w, in]() {
      MotionField * m = workOutMotion(in);
      if (!PostMessageW(w, WM_MOTION, (WPARAM) in.job, (LPARAM) m)) delete m;
    }).detach();
  }

  // The answer from the thread: kept if it is the newest and the view hasn't moved since.
  void motionArrived(int job, MotionField * m)
  {
    Mover & mv = movers[m->which];
    if (job == mv.job) {
      mv.running = false;
      if (target && m->view == currentView()) {
        std::vector<uint8_t> px((size_t) m->bw * m->bh * 4);
        for (size_t k = 0; k < m->vx.size(); ++k) {
          px[k * 4 + 2] = (uint8_t) std::max(0.0f, std::min(255.0f, (0.5f + m->vx[k] / (2 * m->vmax)) * 255 + 0.5f));   // red: x
          px[k * 4 + 1] = (uint8_t) std::max(0.0f, std::min(255.0f, (0.5f + m->vy[k] / (2 * m->vmax)) * 255 + 0.5f));   // green: y
          px[k * 4 + 3] = 255;
        }
        D2D1_BITMAP_PROPERTIES props = D2D1::BitmapProperties(D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_PREMULTIPLIED));
        if (mv.map) { mv.map->Release(); mv.map = nullptr; }
        if (SUCCEEDED(target->CreateBitmap(D2D1::SizeU(m->bw, m->bh), px.data(), m->bw * 4, props, &mv.map)))
          mv.field = *m;
      }
    }
    delete m;
  }

  bool ensureLayer(ID2D1BitmapRenderTarget *& layer)
  {
    const UINT32 w = (UINT32) mapWidth(), h = (UINT32) mapHeight();
    if (layer) {
      D2D1_SIZE_U have = layer->GetPixelSize();
      if (have.width != w || have.height != h) { layer->Release(); layer = nullptr; }
    }
    if (!layer && FAILED(target->CreateCompatibleRenderTarget(D2D1::SizeF((float) w, (float) h), &layer)))
      layer = nullptr;
    return layer != nullptr;
  }

  // A weather layer between pictures layerA and layerB, t of the way (0..1),
  // moved along its motion field.  False if it can't be (then the caller
  // cross-fades or steps).
  bool drawMoving(int which, int layerA, int layerB, int maxZoom, float t, float opacity)
  {
    Mover & mv = movers[which];
    if (!context || !motionReady(which)) return false;
    if (!ensureLayer(mv.layerA) || !ensureLayer(mv.layerB)) return false;
    auto make = [](REFCLSID id, ID2D1Effect *& fx) { if (!fx && FAILED(context->CreateEffect(id, &fx))) fx = nullptr; return fx != nullptr; };
    if (!make(CLSID_D2D1Scale, mv.fxScale) || !make(CLSID_D2D1DisplacementMap, mv.fxMoveA) || !make(CLSID_D2D1DisplacementMap, mv.fxMoveB) ||
        !make(CLSID_D2D1ArithmeticComposite, mv.fxMix) || !make(CLSID_D2D1GaussianBlur, mv.fxBlur))
      return false;

    ID2D1Bitmap * a = nullptr, * b = nullptr;
    mv.layerA->BeginDraw();
    mv.layerA->Clear(D2D1::ColorF(0, 0, 0, 0));
    drawLayer(layerA, maxZoom, 1.0f, mv.layerA);
    mv.layerB->BeginDraw();
    mv.layerB->Clear(D2D1::ColorF(0, 0, 0, 0));
    drawLayer(layerB, maxZoom, 1.0f, mv.layerB);
    const bool drawn = SUCCEEDED(mv.layerA->EndDraw()) & SUCCEEDED(mv.layerB->EndDraw());
    if (!drawn || FAILED(mv.layerA->GetBitmap(&a)) || FAILED(mv.layerB->GetBitmap(&b))) {
      if (a) a->Release();
      return false;
    }

    // the field, from its small picture up to the view (holding its edges)
    mv.fxScale->SetInput(0, mv.map);
    mv.fxScale->SetValue(D2D1_SCALE_PROP_SCALE, D2D1::Vector2F(mv.field.blockPx, mv.field.blockPx));
    mv.fxScale->SetValue(D2D1_SCALE_PROP_INTERPOLATION_MODE, D2D1_SCALE_INTERPOLATION_MODE_LINEAR);
    mv.fxScale->SetValue(D2D1_SCALE_PROP_BORDER_MODE, D2D1_BORDER_MODE_HARD);
    // A moved forward t of the way (shown at p: A at p - t v), B moved back (B at p + (1 - t) v)
    const float span = 2 * mv.field.vmax;
    auto setMove = [&](ID2D1Effect * fx, ID2D1Bitmap * picture, float amount) {
      fx->SetInput(0, picture);
      fx->SetInputEffect(1, mv.fxScale);
      fx->SetValue(D2D1_DISPLACEMENTMAP_PROP_SCALE, amount);
      fx->SetValue(D2D1_DISPLACEMENTMAP_PROP_X_CHANNEL_SELECT, D2D1_CHANNEL_SELECTOR_R);
      fx->SetValue(D2D1_DISPLACEMENTMAP_PROP_Y_CHANNEL_SELECT, D2D1_CHANNEL_SELECTOR_G);
    };
    setMove(mv.fxMoveA, a, -t * span);
    setMove(mv.fxMoveB, b, (1 - t) * span);
    // (1 - t) A + t B
    mv.fxMix->SetInputEffect(0, mv.fxMoveA);
    mv.fxMix->SetInputEffect(1, mv.fxMoveB);
    mv.fxMix->SetValue(D2D1_ARITHMETICCOMPOSITE_PROP_COEFFICIENTS, D2D1::Vector4F(0, 1 - t, t, 0));

    const float cell = (float) pow(2.0, zoom + log2(ui) - levelFor(maxZoom));
    context->PushLayer(D2D1::LayerParameters1(D2D1::InfiniteRect(), nullptr, D2D1_ANTIALIAS_MODE_ALIASED,
                                              D2D1::IdentityMatrix(), opacity), nullptr);
    if (cell >= 1.6f) {
      mv.fxBlur->SetInputEffect(0, mv.fxMix);
      mv.fxBlur->SetValue(D2D1_GAUSSIANBLUR_PROP_STANDARD_DEVIATION, 0.32f * cell);
      mv.fxBlur->SetValue(D2D1_GAUSSIANBLUR_PROP_BORDER_MODE, D2D1_BORDER_MODE_SOFT);
      context->DrawImage(mv.fxBlur);
      mv.fxBlur->SetInput(0, nullptr);
    }
    else
      context->DrawImage(mv.fxMix);
    context->PopLayer();
    mv.fxMoveA->SetInput(0, nullptr);
    mv.fxMoveB->SetInput(0, nullptr);
    a->Release();
    b->Release();
    return true;
  }

  // ------------------------------------------------------------- data refresh
  void matchSatFrames();                   // (below)

  void checkRadar()
  {
    radarCheckedAt = GetTickCount64();
    net::getText(L"https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.json", [](net::Result & r) {
      if (!r.ok()) return;
      std::string valid = Json::parse(r.body)["meta"]["valid"].str();
      if (valid.empty() || valid == radarValid) return;
      bool first = radarValid.empty();
      radarValid = valid;
      parseIso(valid, radarValidTime);
      if (!first) dropRadarTiles();         // (the frames' names now mean newer pictures)
      for (std::string & s : satFrame) s.clear();
      matchSatFrames();
      requestView();
      InvalidateRect(window, nullptr, FALSE);
    });
  }

  // Each radar frame gets the satellite picture nearest its time (within 8
  // minutes); when that changes, the old cloud tiles go.
  void matchSatFrames()
  {
    if (radarValid.empty() || satTimes.empty()) return;
    bool changed = false;
    for (int f = 0; f < FrameCount; ++f) {
      FILETIME want = addMinutes(radarValidTime, -(FrameCount - 1 - f) * 5);
      ULARGE_INTEGER w; w.LowPart = want.dwLowDateTime; w.HighPart = want.dwHighDateTime;
      std::string best;
      double bestGap = 8 * 60;
      for (const std::string & s : satTimes) {
        FILETIME t;
        if (!parseIso(s, t)) continue;
        ULARGE_INTEGER v; v.LowPart = t.dwLowDateTime; v.HighPart = t.dwHighDateTime;
        double gap = fabs((double) ((LONGLONG) v.QuadPart - (LONGLONG) w.QuadPart)) / 1e7;
        if (gap <= bestGap) { bestGap = gap; best = s; }
      }
      if (best != satFrame[f]) { satFrame[f] = best; changed = true; }
    }
    if (changed) {
      dropMotion(CloudMotion);
      for (auto it = tiles.begin(); it != tiles.end();) {
        if (isSatLayer((int) (it->first >> 56))) { dropTile(it->second); it = tiles.erase(it); }
        else ++it;
      }
      requestView();
      InvalidateRect(window, nullptr, FALSE);
    }
  }

  // The times nowCOAST has GOES infrared pictures for (its WMS capabilities)
  void checkSatellite()
  {
    satCheckedAt = GetTickCount64();
    net::getText(L"https://nowcoast.noaa.gov/geoserver/satellite/wms?service=WMS&version=1.3.0&request=GetCapabilities", [](net::Result & r) {
      if (!r.ok()) return;
      size_t at = r.body.find("<Name>goes_longwave_imagery</Name>");
      if (at == std::string::npos) return;
      size_t dim = r.body.find("<Dimension name=\"time\"", at);
      size_t open = dim == std::string::npos ? dim : r.body.find('>', dim);
      size_t close = open == std::string::npos ? open : r.body.find('<', open);
      if (close == std::string::npos) return;
      std::vector<std::string> times;
      std::string list = r.body.substr(open + 1, close - open - 1);
      for (size_t a = 0; a < list.size();) {
        size_t b = list.find(',', a);
        if (b == std::string::npos) b = list.size();
        std::string t = list.substr(a, b - a);
        while (!t.empty() && isspace((unsigned char) t.back())) t.pop_back();
        while (!t.empty() && isspace((unsigned char) t.front())) t.erase(0, 1);
        if (!t.empty()) times.push_back(t);
        a = b + 1;
      }
      if (times.empty()) return;
      satTimes.swap(times);
      matchSatFrames();
    });
  }

  void loadAlerts()
  {
    alertsAt = GetTickCount64();
    nws::loadAlerts([](bool ok, std::vector<nws::Alert> & list) {
      if (!ok) return;                      // (keep the last ones)
      alerts.swap(list);
      alertsOk = true;
      InvalidateRect(window, nullptr, FALSE);
    });
  }

  void loadForecast(double lat, double lon, const std::string & name)
  {
    picked.lat = lat;
    picked.lon = lon;
    picked.name = name;
    picked.set = true;
    forecastAt = GetTickCount64();
    forecast = nws::Forecast();
    forecast.lat = lat;
    forecast.lon = lon;
    forecast.waiting = 1;
    panelScroll = 0;
    nws::loadForecast(lat, lon, [](const nws::Forecast & f) {
      forecast = f;
      // the home place takes its name from the NWS when it has none
      if (home.set && home.name.empty() && fabs(f.lat - home.lat) < 1e-6 && fabs(f.lon - home.lon) < 1e-6 && !f.place.empty()) {
        home.name = f.place;
        saveSettings();
      }
      InvalidateRect(window, nullptr, FALSE);
    });
  }

  void centerOn(double lat, double lon, double z)
  {
    camX = worldX(lon);
    camY = worldY(lat);
    zoom = z;
    requestView();
    InvalidateRect(window, nullptr, FALSE);
  }

  void setHome(double lat, double lon, const std::string & name)
  {
    home.lat = lat;
    home.lon = lon;
    home.name = name;
    home.set = true;
    status.clear();
    saveSettings();
  }

  // Search: a place name, through OpenStreetMap's Nominatim (US first)
  void search(const std::wstring & text)
  {
    if (text.empty()) return;
    std::string q = narrow(text), escaped;
    for (unsigned char c : q) {
      if (isalnum(c) || c == '-' || c == '.' || c == '_') escaped += (char) c;
      else { char hex[4]; sprintf_s(hex, "%%%02X", c); escaped += hex; }
    }
    status = L"Searching for " + text + L"...";
    InvalidateRect(window, nullptr, FALSE);
    net::getText(L"https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=us,pr,vi,gu,as,mp&q=" + wide(escaped),
                 [text](net::Result & r) {
      Json found = Json::parse(r.body);
      const Json & first = found[(size_t) 0];
      if (!r.ok() || !first.isObject()) {
        status = L"Nothing found for \"" + text + L"\".";
        InvalidateRect(window, nullptr, FALSE);
        return;
      }
      double lat = atof(first["lat"].str().c_str()), lon = atof(first["lon"].str().c_str());
      status.clear();
      centerOn(lat, lon, std::max(zoom, 9.0));
      loadForecast(lat, lon, "");
    });
  }

  // ------------------------------------------------------------- drawing
  void text(const std::wstring & s, IDWriteTextFormat * font, D2D1_RECT_F r, uint32_t color, float alpha = 1.0f)
  {
    brush->SetColor(rgb(color, alpha));
    target->DrawTextW(s.c_str(), (UINT32) s.size(), font, r, brush, D2D1_DRAW_TEXT_OPTIONS_CLIP);
  }

  // the height a text takes in a width
  float textHeight(const std::wstring & s, IDWriteTextFormat * font, float width)
  {
    IDWriteTextLayout * layout = nullptr;
    float h = 0;
    if (SUCCEEDED(dwrite->CreateTextLayout(s.c_str(), (UINT32) s.size(), font, width, 10000, &layout))) {
      DWRITE_TEXT_METRICS m;
      layout->GetMetrics(&m);
      h = m.height;
      layout->Release();
    }
    return h;
  }

  void drawAlerts()
  {
    if (!showAlerts) return;
    for (const nws::Alert & a : alerts)
      for (const nws::Ring & ring : a.rings) {
        ID2D1PathGeometry * path = nullptr;
        ID2D1GeometrySink * sink = nullptr;
        if (FAILED(d2d->CreatePathGeometry(&path))) continue;
        if (SUCCEEDED(path->Open(&sink))) {
          for (size_t i = 0; i < ring.lon.size(); ++i) {
            D2D1_POINT_2F p = toScreen(worldX(ring.lon[i]), worldY(ring.lat[i]));
            if (i == 0) sink->BeginFigure(p, D2D1_FIGURE_BEGIN_FILLED);
            else sink->AddLine(p);
          }
          sink->EndFigure(D2D1_FIGURE_END_CLOSED);
          sink->Close();
          sink->Release();
          brush->SetColor(rgb(a.color, 0.18f));
          target->FillGeometry(path, brush);
          brush->SetColor(rgb(0x000000, 0.6f));
          target->DrawGeometry(path, brush, 4.0f * ui);
          brush->SetColor(rgb(a.color));
          target->DrawGeometry(path, brush, 2.0f * ui);
        }
        path->Release();
      }
  }

  void drawPin(double lat, double lon, uint32_t color)
  {
    D2D1_POINT_2F p = toScreen(worldX(lon), worldY(lat));
    brush->SetColor(rgb(0x000000, 0.5f));
    target->FillEllipse(D2D1::Ellipse(p, 8 * ui, 8 * ui), brush);
    brush->SetColor(rgb(0xFFFFFF));
    target->FillEllipse(D2D1::Ellipse(p, 6.5f * ui, 6.5f * ui), brush);
    brush->SetColor(rgb(color));
    target->FillEllipse(D2D1::Ellipse(p, 4.5f * ui, 4.5f * ui), brush);
  }

  // the radar's colors, dBZ 5..75 (as NOAA and the IEM draw them)
  void drawLegend(float x, float y, float w)
  {
    static const uint32_t colors[] = { 0x04E9E7, 0x019FF4, 0x0300F4, 0x02FD02, 0x01C501, 0x008E00, 0xFDF802,
                                       0xE5BC00, 0xFD9500, 0xFD0000, 0xD40000, 0xBC0000, 0xF800FD, 0x9854C6, 0xFDFDFD };
    const int n = _countof(colors);
    float cell = w / n, h = 8 * ui;
    for (int i = 0; i < n; ++i) {
      brush->SetColor(rgb(colors[i]));
      target->FillRectangle(D2D1::RectF(x + i * cell, y, x + (i + 1) * cell + 0.5f, y + h), brush);
    }
    text(L"Light", fontSmall, D2D1::RectF(x, y + h + 1, x + w, y + h + 16 * ui), 0xFFFFFF);
    IDWriteTextFormat * f = fontSmall;
    f->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_TRAILING);
    text(L"Heavy", f, D2D1::RectF(x, y + h + 1, x + w, y + h + 16 * ui), 0xFFFFFF);
    f->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_LEADING);
  }

  void drawMapOverlay()
  {
    const float W = mapWidth(), H = mapHeight();
    // the radar's time and the loop's state, top left
    float bx = 10 * ui, by = 10 * ui, bw = 230 * ui, bh = 88 * ui;
    brush->SetColor(rgb(0x101820, 0.78f));
    target->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(bx, by, bx + bw, by + bh), 6 * ui, 6 * ui), brush);
    std::wstring when = radarValid.empty() ? L"Radar: loading..."
                      : L"Radar " + clockTime(addMinutes(radarValidTime, -(FrameCount - 1 - frame) * 5));
    text(when, fontBold, D2D1::RectF(bx + 10 * ui, by + 6 * ui, bx + bw, by + 30 * ui), 0xFFFFFF);
    std::wstring state = playing ? L"▶  playing  (Space)" : L"❚❚  paused  (Space, ← →)";
    if ((showRadar && motionReady(RadarMotion)) || (showClouds && motionReady(CloudMotion))) state += L"   • smooth motion";
    text(state, fontSmall, D2D1::RectF(bx + 10 * ui, by + 28 * ui, bx + bw, by + 46 * ui), 0xC8D6E5);
    // the loop's frames, as a row of ticks
    float tx = bx + 10 * ui, tw = (bw - 20 * ui) / FrameCount;
    for (int f = 0; f < FrameCount; ++f) {
      brush->SetColor(f == frame ? rgb(0xFFD166) : rgb(0x5C7080));
      target->FillRectangle(D2D1::RectF(tx + f * tw + 1, by + 50 * ui, tx + (f + 1) * tw - 1, by + 54 * ui), brush);
    }
    drawLegend(bx + 10 * ui, by + 58 * ui, bw - 20 * ui);

    // the Layers box, top right; the zoom buttons, bottom right
    hits.clear();
    {
      const float lw = 190 * ui, row = 26 * ui, lx = W - lw - 10 * ui, ly = 10 * ui;
      struct { const wchar_t * name; bool on; int what; } rows[] = {
        { L"Radar", showRadar, HitRadar }, { L"Clouds (satellite)", showClouds, HitClouds }, { L"Warnings", showAlerts, HitWarnings } };
      const float lh = 30 * ui + row * _countof(rows);
      brush->SetColor(rgb(0x101820, 0.78f));
      target->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(lx, ly, lx + lw, ly + lh), 6 * ui, 6 * ui), brush);
      text(L"Layers", fontBold, D2D1::RectF(lx + 10 * ui, ly + 5 * ui, lx + lw, ly + 28 * ui), 0xFFFFFF);
      for (size_t i = 0; i < _countof(rows); ++i) {
        float ry = ly + 30 * ui + i * row;
        D2D1_RECT_F box = D2D1::RectF(lx + 10 * ui, ry + 4 * ui, lx + 26 * ui, ry + 20 * ui);
        brush->SetColor(rows[i].on ? rgb(0x2E86DE) : rgb(0x3A4B5C));
        target->FillRoundedRectangle(D2D1::RoundedRect(box, 3 * ui, 3 * ui), brush);
        if (rows[i].on) {
          brush->SetColor(rgb(0xFFFFFF));
          target->DrawLine(D2D1::Point2F(box.left + 3.5f * ui, box.top + 8.5f * ui), D2D1::Point2F(box.left + 6.5f * ui, box.top + 11.5f * ui), brush, 2 * ui);
          target->DrawLine(D2D1::Point2F(box.left + 6.5f * ui, box.top + 11.5f * ui), D2D1::Point2F(box.left + 12.5f * ui, box.top + 4.5f * ui), brush, 2 * ui);
        }
        text(rows[i].name, fontText, D2D1::RectF(lx + 34 * ui, ry + 2 * ui, lx + lw, ry + row), rows[i].on ? 0xFFFFFF : 0x9FB0C2);
        hits.push_back({ D2D1::RectF(lx, ry, lx + lw, ry + row), rows[i].what });
      }
      const float bs = 34 * ui, zx = W - bs - 10 * ui, zy = H - 20 * ui - 2 * bs - 18 * ui;
      const wchar_t * signs[] = { L"+", L"\u2212" };
      for (int i = 0; i < 2; ++i) {
        D2D1_RECT_F b = D2D1::RectF(zx, zy + i * (bs + 4 * ui), zx + bs, zy + i * (bs + 4 * ui) + bs);
        brush->SetColor(rgb(0x101820, 0.78f));
        target->FillRoundedRectangle(D2D1::RoundedRect(b, 6 * ui, 6 * ui), brush);
        fontBig->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER);
        fontBig->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_CENTER);
        text(signs[i], fontBig, b, 0xFFFFFF);
        fontBig->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_LEADING);
        fontBig->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_NEAR);
        hits.push_back({ b, i == 0 ? HitZoomIn : HitZoomOut });
      }
    }

    // the credits and the status, along the bottom
    std::wstring credits = mapCredit + L"  •  Radar: NOAA NEXRAD via Iowa Environmental Mesonet  •  Clouds: NOAA GOES via nowCOAST  •  Forecasts & alerts: National Weather Service";
    int busy = net::busy();
    std::wstring line = !status.empty() ? status : busy ? format(L"Loading (%d)...", busy) : L"";
    brush->SetColor(rgb(0x101820, 0.7f));
    target->FillRectangle(D2D1::RectF(0, H - 20 * ui, W, H), brush);
    text(credits, fontSmall, D2D1::RectF(8 * ui, H - 18 * ui, W - 8 * ui, H), 0xDDE3EA);
    if (!line.empty()) {
      brush->SetColor(rgb(0x101820, 0.78f));
      float lw = 12 * ui + textHeight(line, fontSmall, 2000) * 0 + (float) line.size() * 7 * ui;
      const float sy = 124 * ui;
      target->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(W - lw - 10 * ui, sy, W - 10 * ui, sy + 22 * ui), 5 * ui, 5 * ui), brush);
      text(line, fontSmall, D2D1::RectF(W - lw - 4 * ui, sy + 3 * ui, W - 10 * ui, sy + 22 * ui), 0xFFFFFF);
    }
  }

  // The side panel: where, now, the alerts there, the next hours and days.
  void drawPanel()
  {
    RECT rc = client();
    const float x0 = mapWidth(), W = panelWidth * ui, H = (float) rc.bottom;
    brush->SetColor(rgb(0x18222D));
    target->FillRectangle(D2D1::RectF(x0, 0, x0 + W, H), brush);

    const float pad = 14 * ui, left = x0 + pad, right = x0 + W - pad, width = right - left;
    const float top = 44 * ui;                          // (below the search box)
    target->PushAxisAlignedClip(D2D1::RectF(x0, top, x0 + W, H), D2D1_ANTIALIAS_MODE_ALIASED);
    float y = top + 6 * ui - panelScroll;
    alertRows.clear();

    auto line = [&](const std::wstring & s, IDWriteTextFormat * font, uint32_t color, float gap = 2) {
      float h = textHeight(s, font, width);
      text(s, font, D2D1::RectF(left, y, right, y + h + 2), color);
      y += h + gap * ui;
    };
    auto rule = [&]() {
      y += 6 * ui;
      brush->SetColor(rgb(0x33475B));
      target->FillRectangle(D2D1::RectF(left, y, right, y + 1), brush);
      y += 8 * ui;
    };

    if (!picked.set) {
      line(L"Right-click the map and choose \"Forecast here\", or search for a place above.", fontText, 0xC8D6E5);
    }
    else {
      std::wstring where = !forecast.place.empty() ? wide(forecast.place) : !picked.name.empty() ? wide(picked.name) : L"This point";
      bool isHome = home.set && fabs(picked.lat - home.lat) < 1e-6 && fabs(picked.lon - home.lon) < 1e-6;
      line(where + (isHome ? L"  ⌂" : L""), fontBig, 0xFFFFFF, 0);
      line(format(L"%.3f°, %.3f°%s", picked.lat, picked.lon, forecast.office.empty() ? L"" : (L"   NWS " + wide(forecast.office)).c_str()),
           fontSmall, 0x8FA3B8, 6);

      if (!forecast.error.empty())
        line(wide(forecast.error), fontText, 0xFFB4A2, 6);

      // now
      const nws::Observation & o = forecast.now;
      if (!std::isnan(o.tempC)) {
        float h = textHeight(L"88°", fontHuge, width);
        text(format(L"%.0f°", o.tempC * 9 / 5 + 32), fontHuge, D2D1::RectF(left, y, right, y + h), 0xFFFFFF);
        text(wide(o.text), fontBold, D2D1::RectF(left + 110 * ui, y + 8 * ui, right, y + h), 0xE8EEF4);
        std::wstring when;
        FILETIME t;
        if (parseIso(o.time, t)) when = L"at " + clockTime(t);
        text(wide(o.station) + L"  " + when, fontSmall, D2D1::RectF(left + 110 * ui, y + 34 * ui, right, y + h), 0x8FA3B8);
        y += h + 2 * ui;
        std::wstring details;
        if (!std::isnan(o.windKmh)) {
          details += o.windKmh < 1 ? L"Wind calm" : format(L"Wind %s %.0f mph", std::isnan(o.windDegrees) ? L"" : compass(o.windDegrees).c_str(), o.windKmh * 0.621371);
          if (!std::isnan(o.gustKmh)) details += format(L", gusts %.0f", o.gustKmh * 0.621371);
          details += L"\n";
        }
        if (!std::isnan(o.humidity)) details += format(L"Humidity %.0f%%", o.humidity);
        if (!std::isnan(o.dewpointC)) details += format(L"   Dew point %.0f°", o.dewpointC * 9 / 5 + 32);
        if (!std::isnan(o.pressurePa)) details += format(L"\nPressure %.2f in", o.pressurePa * 0.00029530);
        if (!std::isnan(o.visibilityM)) details += format(L"   Visibility %.0f mi", o.visibilityM / 1609.34);
        line(details, fontText, 0xC8D6E5, 4);
      }
      else if (forecast.waiting > 0)
        line(L"Loading...", fontText, 0x8FA3B8);

      // alerts in effect here
      for (size_t i = 0; i < forecast.alerts.size(); ++i) {
        const nws::Alert & a = forecast.alerts[i];
        y += 4 * ui;
        float h = textHeight(wide(a.event), fontBold, width - 14 * ui) + 6 * ui;
        alertRows.push_back({ D2D1::RectF(left, y, right, y + h + 18 * ui), i });
        brush->SetColor(rgb(a.color));
        target->FillRectangle(D2D1::RectF(left, y, left + 5 * ui, y + h), brush);
        text(wide(a.event), fontBold, D2D1::RectF(left + 12 * ui, y + 2 * ui, right, y + h), 0xFFFFFF);
        y += h;
        line(L"Click here for the details", fontSmall, 0x8FA3B8, 2);
      }

      // the next hours
      if (!forecast.hourly.empty()) {
        rule();
        line(L"Next hours", fontBold, 0xFFFFFF, 6);
        for (size_t i = 0; i < forecast.hourly.size() && i < 12; ++i) {
          const nws::Period & p = forecast.hourly[i];
          float h = std::max(textHeight(L"Xg", fontText, width), textHeight(brief(p.shortForecast), fontText, right - (left + 148 * ui)));
          text(hourOf(p.start), fontText, D2D1::RectF(left, y, left + 60 * ui, y + h), 0xC8D6E5);
          text(format(L"%d°", p.temperature), fontBold, D2D1::RectF(left + 62 * ui, y, left + 105 * ui, y + h), 0xFFFFFF);
          if (p.precipitation > 0)
            text(format(L"%d%%", p.precipitation), fontText, D2D1::RectF(left + 105 * ui, y, left + 145 * ui, y + h), 0x7FC8FF);
          text(brief(p.shortForecast), fontText, D2D1::RectF(left + 148 * ui, y, right, y + h), 0xC8D6E5);
          y += h + 2 * ui;
        }
      }

      // the days
      if (!forecast.daily.empty()) {
        rule();
        line(L"Forecast", fontBold, 0xFFFFFF, 6);
        for (const nws::Period & p : forecast.daily) {
          std::wstring head = wide(p.name) + format(L"   %s %d°", p.daytime ? L"High" : L"Low", p.temperature);
          if (p.precipitation > 0) head += format(L"   %d%%", p.precipitation);
          line(head, fontBold, p.daytime ? 0xFFFFFF : 0xB8C6D6, 1);
          line(wide(p.detailed), fontText, 0xC8D6E5, 8);
        }
      }
    }
    panelHeight = y + panelScroll - top + 20 * ui;
    target->PopAxisAlignedClip();
    // the panel's edge
    brush->SetColor(rgb(0x0B1118));
    target->FillRectangle(D2D1::RectF(x0, 0, x0 + 1, H), brush);
  }

  // ------------------------------------------------------------- the render target
  void releaseDevice()
  {
    dropAllTiles();
    if (brush) { brush->Release(); brush = nullptr; }
    if (blur) { blur->Release(); blur = nullptr; }
    releaseMovers();
    if (weatherLayer) { weatherLayer->Release(); weatherLayer = nullptr; }
    if (context) { context->Release(); context = nullptr; }
    if (target) { target->Release(); target = nullptr; }
  }

  bool makeDevice()
  {
    if (target) return true;
    RECT r = client();
    D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties();
    props.dpiX = props.dpiY = 96;                       // (drawing in pixels; ui scales what needs it)
    if (FAILED(d2d->CreateHwndRenderTarget(props, D2D1::HwndRenderTargetProperties(window, D2D1::SizeU(r.right, r.bottom)), &target)))
      return false;
    target->CreateSolidColorBrush(D2D1::ColorF(1, 1, 1), &brush);
    target->QueryInterface(__uuidof(ID2D1DeviceContext), (void **) &context);
    requestView();
    return true;
  }

  void paint()
  {
    if (!makeDevice()) return;
    target->BeginDraw();
    target->Clear(rgb(0xAAD3DF));                       // (the map's sea, while tiles come)
    const float W = mapWidth(), H = mapHeight();
    target->PushAxisAlignedClip(D2D1::RectF(0, 0, W, H), D2D1_ANTIALIAS_MODE_ALIASED);
    drawLayer(0, MapMaxZoom, 1.0f);
    // (darken the map a little, so the radar's colors stand out)
    brush->SetColor(rgb(0x000000, 0.12f));
    target->FillRectangle(D2D1::RectF(0, 0, W, H), brush);
    // the loop.  With the storms' motion known, the radar moves on all through
    // a picture's time (true in-between pictures); without it, it fades into
    // the next in the last part.  The clouds move too once their motion is
    // known; until then they step.  From the newest back to the oldest: a cut.
    const bool moving = showRadar && motionReady(RadarMotion);
    const bool advancing = playing && frame < FrameCount - 1 && frameLoaded(frame + 1);
    const ULONGLONG held = GetTickCount64() - frameShownAt;
    const float along = advancing ? std::min(1.0f, (float) held / FrameMs) : 0;   // (the moving layers' t)
    int next = frame;
    float t = 0;
    if (advancing) {
      if (moving) {
        next = frame + 1;
        t = along;
      }
      else if (held > FrameMs - FadeMs) {
        next = frame + 1;
        t = std::min(1.0f, (float) (held - (FrameMs - FadeMs)) / FadeMs);
        t = t * t * (3 - 2 * t);                        // (eased at both ends)
      }
    }
    if (showClouds && !(advancing && cloudPair(frame) &&
                        drawMoving(CloudMotion, satLayer(frame), satLayer(frame + 1), SatMaxZoom, along, 0.92f)))
      drawWeather(satLayer(frame), satLayer(frame), 0, SatMaxZoom, 0.92f);
    if (showRadar && !(moving && next != frame && drawMoving(RadarMotion, radarLayer(frame), radarLayer(next), RadarMaxZoom, t, radarOpacity)))
      drawWeather(radarLayer(frame), radarLayer(next), t, RadarMaxZoom, radarOpacity);
    drawAlerts();
    if (home.set) drawPin(home.lat, home.lon, 0x2E86DE);
    if (picked.set && !(home.set && picked.lat == home.lat && picked.lon == home.lon)) drawPin(picked.lat, picked.lon, 0xE67E22);
    drawMapOverlay();
    target->PopAxisAlignedClip();
    drawPanel();
    if (target->EndDraw() == D2DERR_RECREATE_TARGET)
      releaseDevice();
  }

  void makeFonts()
  {
    auto font = [](float size, DWRITE_FONT_WEIGHT weight, IDWriteTextFormat ** out) {
      if (*out) (*out)->Release();
      dwrite->CreateTextFormat(L"Segoe UI", nullptr, weight, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL,
                               size * ui, L"en-us", out);
      (*out)->SetWordWrapping(DWRITE_WORD_WRAPPING_WRAP);
    };
    font(11.5f, DWRITE_FONT_WEIGHT_NORMAL, &fontSmall);
    font(13.5f, DWRITE_FONT_WEIGHT_NORMAL, &fontText);
    font(13.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD, &fontBold);
    font(19.0f, DWRITE_FONT_WEIGHT_SEMI_BOLD, &fontBig);
    font(46.0f, DWRITE_FONT_WEIGHT_LIGHT, &fontHuge);
  }

  // ------------------------------------------------------------- input
  void zoomAt(float sx, float sy, double dz)
  {
    double wx, wy;
    toWorld(sx, sy, wx, wy);
    zoom = std::max(2.0, std::min((double) MapMaxZoom, zoom + dz));
    // keep (wx, wy) under the pointer
    camX = wx - (sx - mapWidth() / 2) / scale();
    camY = wy - (sy - mapHeight() / 2) / scale();
    camY = std::max(0.0, std::min(256.0, camY));
    requestView();
    InvalidateRect(window, nullptr, FALSE);
  }

  const nws::Alert * alertAt(float sx, float sy)
  {
    if (!showAlerts) return nullptr;
    double wx, wy;
    toWorld(sx, sy, wx, wy);
    const double lon = lonOf(wx), lat = latOf(wy);
    for (auto it = alerts.rbegin(); it != alerts.rend(); ++it)
      for (const nws::Ring & r : it->rings) {
        bool inside = false;
        for (size_t i = 0, j = r.lon.size() - 1; i < r.lon.size(); j = i++)
          if (((r.lat[i] > lat) != (r.lat[j] > lat)) &&
              (lon < (r.lon[j] - r.lon[i]) * (lat - r.lat[i]) / (r.lat[j] - r.lat[i]) + r.lon[i]))
            inside = !inside;
        if (inside) return &*it;
      }
    return nullptr;
  }

  void showAlert(const nws::Alert & a)
  {
    std::wstring body = wide(a.headline);
    if (!a.area.empty()) body += L"\n\nAreas: " + wide(a.area);
    if (!a.description.empty()) body += L"\n\n" + wide(a.description);
    if (!a.instruction.empty()) body += L"\n\n" + wide(a.instruction);
    body += L"\n\n— " + wide(a.sender);
    MessageBoxW(window, body.c_str(), wide(a.event).c_str(), MB_OK | (a.severity == "Extreme" || a.severity == "Severe" ? MB_ICONWARNING : MB_ICONINFORMATION));
  }

  enum { CmdForecastHere = 100, CmdHomeHere, CmdGoHome, CmdAlerts, CmdOpacity50, CmdOpacity75, CmdOpacity100, CmdRefresh, CmdAbout };

  void contextMenu(int sx, int sy)
  {
    double wx, wy;
    toWorld((float) sx, (float) sy, wx, wy);
    HMENU menu = CreatePopupMenu();
    AppendMenuW(menu, MF_STRING, CmdForecastHere, L"Forecast here");
    AppendMenuW(menu, MF_STRING, CmdHomeHere, L"Make this my home");
    AppendMenuW(menu, MF_STRING | (home.set ? 0 : MF_GRAYED), CmdGoHome, L"Go home\tHome");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING | (showAlerts ? MF_CHECKED : 0), CmdAlerts, L"Show warnings on the map");
    HMENU opacity = CreatePopupMenu();
    AppendMenuW(opacity, MF_STRING | (radarOpacity < 0.6f ? MF_CHECKED : 0), CmdOpacity50, L"50%");
    AppendMenuW(opacity, MF_STRING | (radarOpacity >= 0.6f && radarOpacity < 0.9f ? MF_CHECKED : 0), CmdOpacity75, L"75%");
    AppendMenuW(opacity, MF_STRING | (radarOpacity >= 0.9f ? MF_CHECKED : 0), CmdOpacity100, L"100%");
    AppendMenuW(menu, MF_POPUP, (UINT_PTR) opacity, L"Radar opacity");
    AppendMenuW(menu, MF_STRING, CmdRefresh, L"Refresh now\tF5");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, CmdAbout, L"About WxRadar...");
    POINT p = { sx, sy };
    ClientToScreen(window, &p);
    int cmd = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, p.x, p.y, 0, window, nullptr);
    DestroyMenu(menu);
    switch (cmd) {
      case CmdForecastHere: loadForecast(latOf(wy), lonOf(wx), ""); break;
      case CmdHomeHere: setHome(latOf(wy), lonOf(wx), ""); loadForecast(home.lat, home.lon, ""); break;
      case CmdGoHome: centerOn(home.lat, home.lon, std::max(zoom, 8.0)); loadForecast(home.lat, home.lon, home.name); break;
      case CmdAlerts: showAlerts = !showAlerts; saveSettings(); break;
      case CmdOpacity50: radarOpacity = 0.5f; saveSettings(); break;
      case CmdOpacity75: radarOpacity = 0.75f; saveSettings(); break;
      case CmdOpacity100: radarOpacity = 1.0f; saveSettings(); break;
      case CmdRefresh: SendMessageW(window, WM_KEYDOWN, VK_F5, 0); break;
      case CmdAbout:
        MessageBoxW(window,
          L"WxRadar 1.0 - weather radar, warnings and forecasts, without ads.\n\n"
          L"Radar: NOAA NEXRAD composite reflectivity, as map tiles from the Iowa Environmental Mesonet (Iowa State University).\n"
          L"Clouds: NOAA GOES East/West longwave infrared, from NOAA nowCOAST.\n"
          L"Forecasts, observations and alerts: the National Weather Service (api.weather.gov).\n"
          L"Map: © OpenStreetMap contributors (openstreetmap.org/copyright); place search: OpenStreetMap Nominatim.\n\n"
          L"Mouse: drag to move, wheel to zoom, click a warning for its text, right-click for more.\n"
          L"Keys: Space play/pause, ← → step through the loop, + - zoom, Home go home, F5 refresh.",
          L"About WxRadar", MB_OK | MB_ICONINFORMATION);
        break;
    }
    InvalidateRect(window, nullptr, FALSE);
  }

  // a click on the panel: on one of the alerts there, its text
  void panelClick(int sx, int sy)
  {
    for (const auto & row : alertRows)
      if (sx >= row.first.left && sx <= row.first.right && sy >= row.first.top && sy <= row.first.bottom &&
          row.second < forecast.alerts.size()) {
        showAlert(forecast.alerts[row.second]);
        return;
      }
  }

  // ------------------------------------------------------------- the window
  LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
  {
    switch (msg) {
      case WM_CREATE: {
        window = hwnd;
        return 0;
      }
      case WM_SIZE: {
        if (target) target->Resize(D2D1::SizeU(LOWORD(lParam), HIWORD(lParam)));
        if (searchBox)
          MoveWindow(searchBox, (int) mapWidth() + (int) (14 * ui), (int) (10 * ui), (int) (panelWidth * ui - 28 * ui), (int) (26 * ui), TRUE);
        requestView();
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;
      }
      case WM_DPICHANGED: {
        ui = HIWORD(wParam) / 96.0f;
        makeFonts();
        const RECT * r = (const RECT *) lParam;
        SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE);
        return 0;
      }
      case WM_PAINT: {
        PAINTSTRUCT ps;
        BeginPaint(hwnd, &ps);
        paint();
        EndPaint(hwnd, &ps);
        return 0;
      }
      case WM_ERASEBKGND:
        return 1;
      case WM_NET:
        net::deliver(lParam);
        return 0;
      case WM_MOTION:
        motionArrived((int) wParam, (MotionField *) lParam);
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;
      case WM_LOCATED: {
        double * where = (double *) lParam;
        if (where && !home.set) {
          setHome(where[0], where[1], "");
          centerOn(home.lat, home.lon, 8);
          loadForecast(home.lat, home.lon, "");
        }
        delete[] where;
        return 0;
      }
      case WM_TIMER: {
        const ULONGLONG now = GetTickCount64();
        // the radar's and the clouds' motion: worked out once the view has been still a moment and every frame is here
        {
          View v = currentView();
          if (!(v == lastView)) {
            lastView = v;
            viewSteadySince = now;
            dropMotion();
          }
          if (now - viewSteadySince > 400 && !radarValid.empty()) {
            const Mover & r = movers[RadarMotion], & c = movers[CloudMotion];
            if (showRadar && r.field.vx.empty() && !r.running && radarLoaded()) startMotion(RadarMotion);
            if (showClouds && c.field.vx.empty() && !c.running && cloudsLoaded()) startMotion(CloudMotion);
          }
        }
        if (playing) InvalidateRect(hwnd, nullptr, FALSE);    // (the fade moves on every tick)
        if (playing && now - frameShownAt >= (frame == FrameCount - 1 ? LastFrameMs : FrameMs)) {
          int next = (frame + 1) % FrameCount;
          if (!frameLoaded(next)) {
            // (anything of it missing is asked for again: nothing is left out for good)
            if (showClouds && !satFrame[next].empty()) requestLayer(satLayer(next), SatMaxZoom);
            if (showRadar) requestLayer(radarLayer(next), RadarMaxZoom);
          }
          if (frameLoaded(next) || now - frameShownAt > 3000) {    // (wait for a frame, a while)
            frame = next;
            frameShownAt = now;
            InvalidateRect(hwnd, nullptr, FALSE);
          }
        }
        if (now - radarCheckedAt > 60 * 1000) checkRadar();
        if (now - alertsAt > 2 * 60 * 1000) loadAlerts();
        if (showClouds && now - satCheckedAt > 5 * 60 * 1000) checkSatellite();
        if (picked.set && now - forecastAt > 15 * 60 * 1000) loadForecast(picked.lat, picked.lon, picked.name);
        static int lastBusy = -1;
        int busy = net::busy();
        if ((busy != 0) != (lastBusy != 0) || (busy && now % 1000 < 100)) InvalidateRect(hwnd, nullptr, FALSE);
        lastBusy = busy;
        return 0;
      }
      case WM_LBUTTONDOWN: {
        SetFocus(hwnd);
        int x = GET_X_LPARAM(lParam), y = GET_Y_LPARAM(lParam);
        if (x >= mapWidth()) { panelClick(x, y); return 0; }
        for (const Hit & h : hits)
          if (x >= h.r.left && x <= h.r.right && y >= h.r.top && y <= h.r.bottom) {
            switch (h.what) {
              case HitRadar: showRadar = !showRadar; break;
              case HitClouds: showClouds = !showClouds; if (showClouds && satTimes.empty()) checkSatellite(); break;
              case HitWarnings: showAlerts = !showAlerts; break;
              case HitZoomIn: zoomAt(mapWidth() / 2, mapHeight() / 2, 1); break;
              case HitZoomOut: zoomAt(mapWidth() / 2, mapHeight() / 2, -1); break;
            }
            saveSettings();
            requestView();
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
          }
        dragging = true;
        dragMoved = false;
        dragStart = dragLast = { x, y };
        SetCapture(hwnd);
        return 0;
      }
      case WM_MOUSEMOVE: {
        if (!dragging) return 0;
        int x = GET_X_LPARAM(lParam), y = GET_Y_LPARAM(lParam);
        if (abs(x - dragStart.x) + abs(y - dragStart.y) > 4) dragMoved = true;
        if (dragMoved) {
          camX -= (x - dragLast.x) / scale();
          camY -= (y - dragLast.y) / scale();
          camY = std::max(0.0, std::min(256.0, camY));
          camX = fmod(fmod(camX, 256.0) + 256.0, 256.0);
          dragLast = { x, y };
          InvalidateRect(hwnd, nullptr, FALSE);
        }
        return 0;
      }
      case WM_LBUTTONUP: {
        if (!dragging) return 0;
        dragging = false;
        ReleaseCapture();
        if (dragMoved) { requestView(); saveSettings(); }
        else if (const nws::Alert * a = alertAt((float) dragStart.x, (float) dragStart.y)) showAlert(*a);
        return 0;
      }
      case WM_LBUTTONDBLCLK: {
        int x = GET_X_LPARAM(lParam), y = GET_Y_LPARAM(lParam);
        for (const Hit & h : hits)          // (a quick second click on a control is just a click)
          if (x >= h.r.left && x <= h.r.right && y >= h.r.top && y <= h.r.bottom)
            return SendMessageW(hwnd, WM_LBUTTONDOWN, wParam, lParam);
        if (x < mapWidth()) zoomAt((float) x, (float) y, 1.0);
        return 0;
      }
      case WM_RBUTTONUP: {
        int x = GET_X_LPARAM(lParam), y = GET_Y_LPARAM(lParam);
        if (x < mapWidth()) contextMenu(x, y);
        return 0;
      }
      case WM_MOUSEWHEEL: {
        POINT p = { GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam) };
        ScreenToClient(hwnd, &p);
        int delta = GET_WHEEL_DELTA_WPARAM(wParam);
        MSG more;                                        // (and the turns already waiting, as one)
        while (PeekMessageW(&more, hwnd, WM_MOUSEWHEEL, WM_MOUSEWHEEL, PM_REMOVE))
          delta += GET_WHEEL_DELTA_WPARAM(more.wParam);
        if (p.x >= mapWidth()) {
          panelScroll -= delta / 120.0f * 60 * ui;
          panelScroll = std::max(0.0f, std::min(panelScroll, std::max(0.0f, panelHeight - (mapHeight() - 44 * ui))));
          InvalidateRect(hwnd, nullptr, FALSE);
        }
        else
          zoomAt((float) p.x, (float) p.y, delta / 120.0 * 0.5);
        return 0;
      }
      case WM_KEYDOWN: {
        switch (wParam) {
          case VK_SPACE: playing = !playing; frame = playing ? frame : FrameCount - 1; frameShownAt = GetTickCount64(); break;
          case VK_LEFT: playing = false; frame = (frame + FrameCount - 1) % FrameCount; break;
          case VK_RIGHT: playing = false; frame = (frame + 1) % FrameCount; break;
          case VK_ADD: case VK_OEM_PLUS: zoomAt(mapWidth() / 2, mapHeight() / 2, 1); break;
          case VK_SUBTRACT: case VK_OEM_MINUS: zoomAt(mapWidth() / 2, mapHeight() / 2, -1); break;
          case VK_HOME: if (home.set) { centerOn(home.lat, home.lon, std::max(zoom, 8.0)); loadForecast(home.lat, home.lon, home.name); } break;
          case VK_F5:
            dropRadarTiles();
            radarValid.clear();
            checkRadar();
            loadAlerts();
            if (picked.set) loadForecast(picked.lat, picked.lon, picked.name);
            requestView();
            break;
          default: return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        requestView();
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;
      }
      case WM_COMMAND:
        return 0;
      case WM_GETMINMAXINFO: {
        MINMAXINFO * m = (MINMAXINFO *) lParam;
        m->ptMinTrackSize = { (LONG) (700 * ui), (LONG) (450 * ui) };
        return 0;
      }
      case WM_CLOSE:
        saveSettings();
        DestroyWindow(hwnd);
        return 0;
      case WM_DESTROY:
        KillTimer(hwnd, TimerTick);
        net::stop();
        releaseDevice();
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
  }

  // The search box: Enter searches.
  WNDPROC searchBoxProc = nullptr;
  LRESULT CALLBACK searchProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
  {
    if (msg == WM_KEYDOWN && wParam == VK_RETURN) {
      wchar_t text[256];
      GetWindowTextW(hwnd, text, _countof(text));
      search(text);
      SetFocus(window);
      return 0;
    }
    if (msg == WM_CHAR && (wParam == VK_RETURN || wParam == VK_ESCAPE)) return 0;    // (no beep)
    if (msg == WM_KEYDOWN && wParam == VK_ESCAPE) { SetFocus(window); return 0; }
    return CallWindowProcW(searchBoxProc, hwnd, msg, wParam, lParam);
  }
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show)
{
  SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  // settings in %APPDATA%\WxRadar, map tiles in %LOCALAPPDATA%\WxRadar\tiles
  wchar_t * folder = nullptr;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &folder))) {
    std::wstring dir = std::wstring(folder) + L"\\WxRadar";
    SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
    settingsFile = dir + L"\\WxRadar.ini";
    CoTaskMemFree(folder);
  }
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &folder))) {
    cacheFolder = std::wstring(folder) + L"\\WxRadar\\tiles";
    CoTaskMemFree(folder);
  }

  D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, __uuidof(ID2D1Factory1), nullptr, (void **) &d2d);
  DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory), (IUnknown **) &dwrite);

  WNDCLASSEXW wc = { sizeof(wc) };
  wc.style = CS_DBLCLKS;
  wc.lpfnWndProc = proc;
  wc.hInstance = instance;
  wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
  wc.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(1));
  wc.lpszClassName = L"WxRadar";
  RegisterClassExW(&wc);

  // the last place and view
  std::wstring h = setting(L"HomeLat");
  if (!h.empty()) {
    home.lat = _wtof(h.c_str());
    home.lon = _wtof(setting(L"HomeLon").c_str());
    home.name = narrow(setting(L"HomeName"));
    home.set = true;
  }
  std::wstring v = setting(L"ViewLat");
  if (!v.empty()) {
    camY = worldY(_wtof(v.c_str()));
    camX = worldX(_wtof(setting(L"ViewLon").c_str()));
    zoom = _wtof(setting(L"Zoom", L"6").c_str());
  }
  else if (home.set) { camX = worldX(home.lon); camY = worldY(home.lat); zoom = 8; }
  else { camX = worldX(-96.5); camY = worldY(38.5); zoom = 4; }           // (the lower 48)
  zoom = std::max(2.0, std::min((double) MapMaxZoom, zoom));
  radarOpacity = (float) _wtof(setting(L"RadarOpacity", L"0.75").c_str());
  showAlerts = setting(L"ShowAlerts", L"1") != L"0";
  showRadar = setting(L"ShowRadar", L"1") != L"0";
  showClouds = setting(L"ShowClouds", L"0") != L"0";
  mapTiles = setting(L"MapTiles", OsmTiles);
  if (!setting(L"MapCredit").empty()) mapCredit = setting(L"MapCredit");

  int x = CW_USEDEFAULT, y = CW_USEDEFAULT, w = 1400, hgt = 900, maximized = 0;
  swscanf_s(setting(L"Window").c_str(), L"%d %d %d %d %d", &x, &y, &w, &hgt, &maximized);
  if (x != CW_USEDEFAULT) { w -= x; hgt -= y; }
  HWND hwnd = CreateWindowExW(0, L"WxRadar", L"WxRadar", WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN,
                              x, y, w, hgt, nullptr, nullptr, instance, nullptr);
  ui = GetDpiForWindow(hwnd) / 96.0f;
  if (x == CW_USEDEFAULT) {
    RECT r; GetWindowRect(hwnd, &r);
    SetWindowPos(hwnd, nullptr, 0, 0, (int) (1400 * ui), (int) (880 * ui), SWP_NOMOVE | SWP_NOZORDER);
  }
  makeFonts();

  searchBox = CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_BORDER | ES_AUTOHSCROLL, 0, 0, 10, 10,
                              hwnd, nullptr, instance, nullptr);
  SendMessageW(searchBox, EM_SETCUEBANNER, TRUE, (LPARAM) L"Search a place (city, ZIP, address) and press Enter");
  HFONT editFont = CreateFontW((int) (-14 * ui), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
  SendMessageW(searchBox, WM_SETFONT, (WPARAM) editFont, TRUE);
  searchBoxProc = (WNDPROC) SetWindowLongPtrW(searchBox, GWLP_WNDPROC, (LONG_PTR) searchProc);

  net::start(hwnd, WM_NET, 4);
  ShowWindow(hwnd, maximized ? SW_SHOWMAXIMIZED : show);
  SendMessageW(hwnd, WM_SIZE, 0, MAKELPARAM(client().right, client().bottom));
  UpdateWindow(hwnd);
  SetTimer(hwnd, TimerTick, 30, nullptr);        // (about 30 a second: the fades)

  checkRadar();
  loadAlerts();
  if (showClouds) checkSatellite();
  if (home.set)
    loadForecast(home.lat, home.lon, home.name);
  else {
    // first run: where is this computer? (Windows location, if it's allowed)
    std::thread([hwnd] {
      double lat, lon, alt;
      bool hasAlt;
      if (location::find(lat, lon, alt, hasAlt))
        PostMessageW(hwnd, WM_LOCATED, 0, (LPARAM) new double[2]{ lat, lon });
    }).detach();
    status = L"Right-click the map: \"Make this my home\" - or search for your town.";
  }

  MSG msg;
  while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
    TranslateMessage(&msg);
    DispatchMessageW(&msg);
  }
  CoUninitialize();
  return 0;
}

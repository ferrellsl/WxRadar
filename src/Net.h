// WxRadar: downloads in the background.
//
// A few worker threads fetch URLs with WinHTTP.  Map tiles go on a stack
// (the newest request first: after a quick pan, the tiles now in view come
// before the ones scrolled past) and can be decoded to pixels on the worker
// thread; API requests (forecasts, alerts) go on a queue of their own, in
// order.  When a download is done, its callback runs on the window's thread
// (the worker posts it a message; the window calls net::deliver).

#pragma once

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace net
{
  struct Result
  {
    int status = 0;                       // HTTP status, 0 if it never got that far
    std::string body;                     // the response (empty for a decoded image)
    // a decoded image: premultiplied BGRA, 4 bytes a pixel
    int width = 0, height = 0;
    std::vector<uint8_t> pixels;
    bool ok() const { return status == 200; }
  };

  using Done = std::function<void(Result &)>;

  // Starts the workers; their results reach the window as message msg.
  void start(HWND window, UINT msg, int workers = 4);
  void stop();

  // A JSON API request (Accept: application/geo+json), in order.
  void getText(const std::wstring & url, Done done);

  // What to do to a tile's pixels once decoded.
  enum Filter
  {
    AsIs,
    Clouds,      // satellite infrared (grey: bright = cold cloud tops) -> white clouds, clear ground transparent
  };

  // A map tile: decoded to pixels.  diskCache: a file to read it from if it
  // is younger than maxAgeHours, and to save it to after a download ("" for
  // none).  Newest request first.
  void getImage(const std::wstring & url, const std::wstring & diskCache, int maxAgeHours, Done done, Filter filter = AsIs);

  // Forget the tile requests not yet started (their callbacks get status 0).
  void dropWaitingImages();

  // To be called by the window for message msg.
  void deliver(LPARAM lParam);

  // How many requests are waiting or under way (for the status line).
  int busy();
}

// WxRadar: downloads in the background (see Net.h).

#include "Net.h"

#include <winhttp.h>
#include <wincodec.h>
#include <shlobj.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace net
{
  namespace
  {
    // Who we are, as the NWS and OpenStreetMap ask every program to say.
    const wchar_t * const UserAgent = L"WxRadar/1.1 (Windows weather radar viewer; +https://github.com/ferrellsl/WxRadar)";

    struct Job
    {
      std::wstring url;
      bool image = false;
      Filter filter = AsIs;
      std::wstring diskCache;
      int maxAgeHours = 0;
      Done done;
      Result result;
    };

    HWND window = nullptr;
    UINT message = 0;
    std::mutex lock;
    std::condition_variable wake;
    std::deque<Job *> texts;            // in order
    std::deque<Job *> images;           // newest at the back, taken from the back
    std::vector<std::thread> workers;
    std::atomic<bool> stopping{false};
    std::atomic<int> active{0};

    bool splitUrl(const std::wstring & url, std::wstring & host, std::wstring & path)
    {
      const std::wstring scheme = L"https://";
      if (url.compare(0, scheme.size(), scheme) != 0) return false;
      size_t slash = url.find(L'/', scheme.size());
      host = url.substr(scheme.size(), slash == std::wstring::npos ? std::wstring::npos : slash - scheme.size());
      path = slash == std::wstring::npos ? L"/" : url.substr(slash);
      return !host.empty();
    }

    void download(HINTERNET session, Job & job)
    {
      std::wstring host, path;
      if (!splitUrl(job.url, host, path)) return;
      HINTERNET connection = WinHttpConnect(session, host.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
      HINTERNET request = connection ? WinHttpOpenRequest(connection, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                                          WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE) : nullptr;
      const wchar_t * headers = job.image ? WINHTTP_NO_ADDITIONAL_HEADERS : L"Accept: application/geo+json\r\n";
      if (request && WinHttpSendRequest(request, headers, job.image ? 0 : (DWORD) -1L, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
          WinHttpReceiveResponse(request, nullptr)) {
        DWORD status = 0, size = sizeof(status);
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
                            &status, &size, WINHTTP_NO_HEADER_INDEX);
        char buffer[65536];
        DWORD read = 0;
        while (WinHttpReadData(request, buffer, sizeof(buffer), &read) && read > 0 && job.result.body.size() < (64u << 20))
          job.result.body.append(buffer, read);
        job.result.status = (int) status;
      }
      if (request) WinHttpCloseHandle(request);
      if (connection) WinHttpCloseHandle(connection);
    }

    bool readFile(const std::wstring & name, std::string & data, int maxAgeHours)
    {
      WIN32_FILE_ATTRIBUTE_DATA info;
      if (!GetFileAttributesExW(name.c_str(), GetFileExInfoStandard, &info)) return false;
      FILETIME now;
      GetSystemTimeAsFileTime(&now);
      const auto ticks = [](FILETIME t) { return ((ULONGLONG) t.dwHighDateTime << 32) | t.dwLowDateTime; };
      if (ticks(now) - ticks(info.ftLastWriteTime) > (ULONGLONG) maxAgeHours * 3600 * 10000000ULL) return false;
      HANDLE file = CreateFileW(name.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
      if (file == INVALID_HANDLE_VALUE) return false;
      data.resize(info.nFileSizeLow);
      DWORD got = 0;
      bool ok = ReadFile(file, data.data(), (DWORD) data.size(), &got, nullptr) && got == data.size();
      CloseHandle(file);
      return ok && !data.empty();
    }

    void writeFile(const std::wstring & name, const std::string & data)
    {
      const size_t slash = name.find_last_of(L'\\');
      if (slash != std::wstring::npos)
        SHCreateDirectoryExW(nullptr, name.substr(0, slash).c_str(), nullptr);
      const std::wstring part = name + L".part";     // (never a half-written tile)
      HANDLE file = CreateFileW(part.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
      if (file == INVALID_HANDLE_VALUE) return;
      DWORD wrote = 0;
      const bool ok = WriteFile(file, data.data(), (DWORD) data.size(), &wrote, nullptr) && wrote == data.size();
      CloseHandle(file);
      if (!ok || !MoveFileExW(part.c_str(), name.c_str(), MOVEFILE_REPLACE_EXISTING))
        DeleteFileW(part.c_str());
    }

    // PNG/JPEG bytes -> premultiplied BGRA pixels (Direct2D's own format)
    bool decode(IWICImagingFactory * wic, const std::string & data, Result & out)
    {
      IWICStream * stream = nullptr;
      IWICBitmapDecoder * decoder = nullptr;
      IWICBitmapFrameDecode * frame = nullptr;
      IWICFormatConverter * converter = nullptr;
      bool ok = false;
      if (SUCCEEDED(wic->CreateStream(&stream)) &&
          SUCCEEDED(stream->InitializeFromMemory((BYTE *) data.data(), (DWORD) data.size())) &&
          SUCCEEDED(wic->CreateDecoderFromStream(stream, nullptr, WICDecodeMetadataCacheOnDemand, &decoder)) &&
          SUCCEEDED(decoder->GetFrame(0, &frame)) &&
          SUCCEEDED(wic->CreateFormatConverter(&converter)) &&
          SUCCEEDED(converter->Initialize(frame, GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0,
                                          WICBitmapPaletteTypeCustom))) {
        UINT w = 0, h = 0;
        converter->GetSize(&w, &h);
        out.pixels.resize((size_t) w * h * 4);
        if (w && h && SUCCEEDED(converter->CopyPixels(nullptr, w * 4, (UINT) out.pixels.size(), out.pixels.data()))) {
          out.width = (int) w;
          out.height = (int) h;
          ok = true;
        }
      }
      if (converter) converter->Release();
      if (frame) frame->Release();
      if (decoder) decoder->Release();
      if (stream) stream->Release();
      return ok;
    }

    // Infrared satellite pictures: the warmer (darker) the less cloud.  Clear
    // ground, darker than about 100 of 255, goes fully transparent; the
    // coldest tops (brightest) are solid white; between, a see-through grey.
    void cloudify(Result & r)
    {
      uint8_t * p = r.pixels.data();
      for (size_t i = 0, n = r.pixels.size() / 4; i < n; ++i, p += 4) {
        const int a = p[3];
        const int grey = a ? (p[0] + p[1] + p[2]) * 255 / (3 * a) : 0;     // (un-premultiplied)
        int alpha = (grey - 100) * 255 / 120;
        alpha = alpha < 0 ? 0 : alpha > 235 ? 235 : alpha;
        alpha = alpha * a / 255;
        const int shade = 170 + grey / 3;                                  // (thicker = whiter)
        const int c = (shade > 255 ? 255 : shade) * alpha / 255;           // premultiplied
        p[0] = p[1] = p[2] = (uint8_t) c;
        p[3] = (uint8_t) alpha;
      }
    }

    void work()
    {
      CoInitializeEx(nullptr, COINIT_MULTITHREADED);
      IWICImagingFactory * wic = nullptr;
      CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&wic));
      HINTERNET session = WinHttpOpen(UserAgent, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME,
                                      WINHTTP_NO_PROXY_BYPASS, 0);
      if (session) {
        WinHttpSetTimeouts(session, 15000, 15000, 30000, 60000);
        DWORD flags = WINHTTP_DECOMPRESSION_FLAG_ALL;     // (gzip: the alerts are much smaller so)
        WinHttpSetOption(session, WINHTTP_OPTION_DECOMPRESSION, &flags, sizeof(flags));
      }

      while (true) {
        Job * job = nullptr;
        {
          std::unique_lock<std::mutex> guard(lock);
          wake.wait(guard, [] { return stopping || !texts.empty() || !images.empty(); });
          if (stopping) break;
          if (!texts.empty()) { job = texts.front(); texts.pop_front(); }
          else { job = images.back(); images.pop_back(); }
          ++active;
        }

        std::string cached;
        if (job->image && !job->diskCache.empty() && readFile(job->diskCache, cached, job->maxAgeHours)) {
          job->result.status = 200;
          job->result.body.swap(cached);
        }
        else if (session) {
          download(session, *job);
          if (job->image && job->result.ok() && !job->diskCache.empty())
            writeFile(job->diskCache, job->result.body);
        }
        if (job->image && job->result.ok()) {
          if (!wic || !decode(wic, job->result.body, job->result))
            job->result.status = -1;                 // (not a picture after all)
          else if (job->filter == Clouds)
            cloudify(job->result);
          job->result.body.clear();
          job->result.body.shrink_to_fit();
        }

        --active;
        if (!PostMessageW(window, message, 0, (LPARAM) job))
          delete job;                                // (the window is gone)
      }

      if (session) WinHttpCloseHandle(session);
      if (wic) wic->Release();
      CoUninitialize();
    }
  }

  void start(HWND w, UINT msg, int count)
  {
    window = w;
    message = msg;
    stopping = false;
    for (int i = 0; i < count; ++i)
      workers.emplace_back(work);
  }

  void stop()
  {
    {
      std::lock_guard<std::mutex> guard(lock);
      stopping = true;
      for (Job * j : texts) delete j;
      for (Job * j : images) delete j;
      texts.clear();
      images.clear();
    }
    wake.notify_all();
    // (a worker in the middle of a download finishes it first; at exit that
    // can take a while, so they are left to end with the process)
    for (std::thread & t : workers)
      t.detach();
    workers.clear();
  }

  void getText(const std::wstring & url, Done done)
  {
    Job * job = new Job;
    job->url = url;
    job->done = std::move(done);
    {
      std::lock_guard<std::mutex> guard(lock);
      texts.push_back(job);
    }
    wake.notify_one();
  }

  void getImage(const std::wstring & url, const std::wstring & diskCache, int maxAgeHours, Done done, Filter filter)
  {
    Job * job = new Job;
    job->url = url;
    job->image = true;
    job->filter = filter;
    job->diskCache = diskCache;
    job->maxAgeHours = maxAgeHours;
    job->done = std::move(done);
    {
      std::lock_guard<std::mutex> guard(lock);
      images.push_back(job);
    }
    wake.notify_one();
  }

  void dropWaitingImages()
  {
    std::deque<Job *> dropped;
    {
      std::lock_guard<std::mutex> guard(lock);
      dropped.swap(images);
    }
    for (Job * job : dropped) {
      job->result.status = 0;
      if (job->done) job->done(job->result);
      delete job;
    }
  }

  void deliver(LPARAM lParam)
  {
    Job * job = (Job *) lParam;
    if (job->done) job->done(job->result);
    delete job;
  }

  int busy()
  {
    std::lock_guard<std::mutex> guard(lock);
    return (int) (texts.size() + images.size()) + active;
  }
}

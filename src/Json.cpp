// WxRadar: a small JSON reader (see Json.h).

#include "Json.h"

#include <cstdlib>
#include <cstring>

class JsonReader
{
public:
  explicit JsonReader(const std::string & s) : p(s.c_str()), end(s.c_str() + s.size()) {}

  bool value(Json & out, int depth = 0)
  {
    if (depth > 200) return false;
    space();
    if (p >= end) return false;
    switch (*p) {
      case '{': return object(out, depth);
      case '[': return array(out, depth);
      case '"': out.kind = Json::String; return string(out.text);
      case 't': return word("true", out, Json::Bool, 1);
      case 'f': return word("false", out, Json::Bool, 0);
      case 'n': return word("null", out, Json::Null, 0);
      default: return number(out);
    }
  }

  bool atEnd() { space(); return p >= end; }

private:
  const char * p;
  const char * end;

  void space() { while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p; }

  bool word(const char * w, Json & out, Json::Type t, double v)
  {
    size_t n = strlen(w);
    if ((size_t) (end - p) < n || strncmp(p, w, n) != 0) return false;
    p += n;
    out.kind = t;
    out.number = v;
    return true;
  }

  bool number(Json & out)
  {
    char * stop = nullptr;
    out.number = strtod(p, &stop);
    if (stop == p) return false;
    p = stop;
    out.kind = Json::Number;
    return true;
  }

  static void utf8(std::string & s, unsigned long c)
  {
    if (c < 0x80) s += (char) c;
    else if (c < 0x800) { s += (char) (0xC0 | (c >> 6)); s += (char) (0x80 | (c & 0x3F)); }
    else if (c < 0x10000) { s += (char) (0xE0 | (c >> 12)); s += (char) (0x80 | ((c >> 6) & 0x3F)); s += (char) (0x80 | (c & 0x3F)); }
    else { s += (char) (0xF0 | (c >> 18)); s += (char) (0x80 | ((c >> 12) & 0x3F)); s += (char) (0x80 | ((c >> 6) & 0x3F)); s += (char) (0x80 | (c & 0x3F)); }
  }

  bool hex4(unsigned long & c)
  {
    if (end - p < 4) return false;
    char h[5] = { p[0], p[1], p[2], p[3], 0 };
    char * stop = nullptr;
    c = strtoul(h, &stop, 16);
    if (stop != h + 4) return false;
    p += 4;
    return true;
  }

  bool string(std::string & s)
  {
    ++p;                                          // (the opening quote)
    while (p < end && *p != '"') {
      if (*p != '\\') { s += *p++; continue; }
      if (++p >= end) return false;
      char e = *p++;
      switch (e) {
        case 'n': s += '\n'; break;
        case 't': s += '\t'; break;
        case 'r': s += '\r'; break;
        case 'b': s += '\b'; break;
        case 'f': s += '\f'; break;
        case 'u': {
          unsigned long c;
          if (!hex4(c)) return false;
          if (c >= 0xD800 && c < 0xDC00 && end - p >= 6 && p[0] == '\\' && p[1] == 'u') {   // a surrogate pair
            p += 2;
            unsigned long low;
            if (!hex4(low)) return false;
            c = 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
          }
          utf8(s, c);
          break;
        }
        default: s += e; break;                   // \" \\ \/
      }
    }
    if (p >= end) return false;
    ++p;
    return true;
  }

  bool array(Json & out, int depth)
  {
    out.kind = Json::Array;
    ++p;
    space();
    if (p < end && *p == ']') { ++p; return true; }
    while (true) {
      out.items.emplace_back();
      if (!value(out.items.back(), depth + 1)) return false;
      space();
      if (p < end && *p == ',') { ++p; continue; }
      if (p < end && *p == ']') { ++p; return true; }
      return false;
    }
  }

  bool object(Json & out, int depth)
  {
    out.kind = Json::Object;
    ++p;
    space();
    if (p < end && *p == '}') { ++p; return true; }
    while (true) {
      space();
      if (p >= end || *p != '"') return false;
      out.members.emplace_back();
      if (!string(out.members.back().first)) return false;
      space();
      if (p >= end || *p != ':') return false;
      ++p;
      if (!value(out.members.back().second, depth + 1)) return false;
      space();
      if (p < end && *p == ',') { ++p; continue; }
      if (p < end && *p == '}') { ++p; return true; }
      return false;
    }
  }
};

Json Json::parse(const std::string & text)
{
  Json out;
  JsonReader reader(text);
  if (!reader.value(out) || !reader.atEnd())
    return Json();
  return out;
}

const Json & Json::operator[](const char * key) const
{
  static const Json none;
  if (kind == Object)
    for (const auto & m : members)
      if (m.first == key) return m.second;
  return none;
}

const Json & Json::operator[](size_t index) const
{
  static const Json none;
  return (kind == Array && index < items.size()) ? items[index] : none;
}

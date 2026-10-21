// WxRadar: a small JSON reader, enough for the NWS's GeoJSON.

#pragma once

#include <string>
#include <utility>
#include <vector>

class Json
{
public:
  enum Type { Null, Bool, Number, String, Array, Object };

  static Json parse(const std::string & text);     // Null if it can't be read

  Type type() const { return kind; }
  bool isNull() const { return kind == Null; }
  bool isArray() const { return kind == Array; }
  bool isObject() const { return kind == Object; }
  bool isNumber() const { return kind == Number; }
  bool isString() const { return kind == String; }

  double num(double otherwise = 0) const { return kind == Number ? number : otherwise; }
  bool boolean() const { return kind == Bool && number != 0; }
  const std::string & str() const { static const std::string none; return kind == String ? text : none; }

  // A member, or Null (never fails: chains like j["a"]["b"][0] are safe).
  const Json & operator[](const char * key) const;
  const Json & operator[](size_t index) const;
  size_t size() const { return kind == Array ? items.size() : kind == Object ? members.size() : 0; }
  const std::vector<Json> & array() const { return items; }
  const std::vector<std::pair<std::string, Json>> & object() const { return members; }

private:
  friend class JsonReader;
  Type kind = Null;
  double number = 0;
  std::string text;
  std::vector<Json> items;
  std::vector<std::pair<std::string, Json>> members;
};

cmake_minimum_required(VERSION 3.15)
project(WxRadar CXX RC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")   # no runtime DLLs to ship

# Location.cpp uses C++/WinRT (Windows.Devices.Geolocation); the Windows 10
# SDK's C++/WinRT needs these switches with Visual Studio 2019.
set_source_files_properties(src/Location.cpp PROPERTIES COMPILE_OPTIONS "/permissive-;/Zc:twoPhase-;/bigobj;/await")

add_executable(WxRadar WIN32
  src/main.cpp src/Net.cpp src/Net.h src/Json.cpp src/Json.h src/Nws.cpp src/Nws.h
  src/Location.cpp src/Location.h res/WxRadar.rc)
target_link_libraries(WxRadar PRIVATE d2d1 dwrite dxguid winhttp windowscodecs ole32 shell32 windowsapp)
target_compile_definitions(WxRadar PRIVATE UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)
target_compile_options(WxRadar PRIVATE /W3 /utf-8)
# (the manifest is in the .rc: no second one from the linker)
target_link_options(WxRadar PRIVATE /MANIFEST:NO)

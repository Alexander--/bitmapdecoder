cmake_minimum_required(VERSION 3.19)

project(pngs)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
# Use debug settings (quick build)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O0")
set(CMAKE_LD_FLAGS "{CMAKE_LD_FLAGS} -O0")
else(CMAKE_BUILD_TYPE STREQUAL "Debug")
# Use release settings (best performance)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -flto -ffast-math -fno-function-sections")
set(CMAKE_LD_FLAGS "{CMAKE_LD_FLAGS} -O3 -flto")
endif(CMAKE_BUILD_TYPE STREQUAL "Debug")

set(JAVA_AWT_LIBRARY NotNeeded)
set(JAVA_AWT_INCLUDE_PATH NotNeeded)
set(JAVA_JVM_LIBRARY NotNeeded)
set(JAVA_INCLUDE_PATH2 NotNeeded)

find_package(JNI REQUIRED)

include_directories(${JAVA_INCLUDE_PATH})
include_directories(${JAVA_INCLUDE_PATH2})

add_library(pngs SHARED pngs.c)

target_link_libraries(pngs log)
target_link_libraries(pngs android)
target_link_libraries(pngs jnigraphics)

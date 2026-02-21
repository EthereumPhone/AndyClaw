cmake_minimum_required(VERSION 3.22)
project(llama_bridge)

# llama.cpp source tree — clone or symlink into app/src/main/cpp/llama.cpp/
# e.g.  git submodule add https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Only build llama.cpp as a static library (no examples, no tests)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    add_subdirectory(${LLAMA_DIR} llama.cpp)
else()
    message(WARNING "llama.cpp not found at ${LLAMA_DIR} — JNI bridge will build but not link")
    # Create stub targets so the JNI bridge compiles without llama.cpp present
    add_library(llama INTERFACE)
    add_library(common INTERFACE)
endif()

# JNI bridge shared library
add_library(llama_bridge SHARED llama_bridge.cpp)

target_include_directories(llama_bridge PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
)

find_library(log-lib log)

target_link_libraries(llama_bridge
    llama
    common
    ${log-lib}
)

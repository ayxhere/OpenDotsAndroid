cmake_minimum_required(VERSION 3.22.1)
project(odnode)

# Imported libnode.so from digidem nodejs-mobile (full build, Node 24.x).
add_library(node SHARED IMPORTED)
set_target_properties(
    node PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so"
)

add_library(odnode SHARED node_bridge.cpp)

target_include_directories(
    odnode PRIVATE
    "${CMAKE_SOURCE_DIR}/../../../../thirdparty/nodejs-mobile/include"
    "${CMAKE_SOURCE_DIR}/../../../../thirdparty/nodejs-mobile/include/node"
)

target_link_libraries(odnode node log)

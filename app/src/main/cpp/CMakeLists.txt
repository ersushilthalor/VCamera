cmake_minimum_required(VERSION 3.22.1)
project(VCameraPhotonEis)

set(MGC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mgc_eis_reconstruction")

add_library(my-native-lib SHARED
    mgc_eis_reconstruction_jni.cpp
    "${MGC_DIR}/mgc_eis_reconstruction.cpp"
    "${MGC_DIR}/mgc_type18_engine.cpp"
    "${MGC_DIR}/mgc_type18_gyro.cpp"
    "${MGC_DIR}/mgc_type18_lookahead.cpp"
    "${MGC_DIR}/mgc_type18_projection.cpp"
)

target_include_directories(my-native-lib PRIVATE "${MGC_DIR}")
target_compile_features(my-native-lib PRIVATE cxx_std_17)
# Preserve deterministic quaternion, look-ahead and homography arithmetic.
target_compile_options(my-native-lib PRIVATE -O3 -fno-fast-math -ffp-contract=off)
target_link_libraries(my-native-lib PRIVATE log android)

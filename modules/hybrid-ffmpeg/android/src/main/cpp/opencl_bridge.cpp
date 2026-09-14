#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <sstream>

typedef int cl_int;
typedef unsigned int cl_uint;
typedef unsigned long cl_ulong;
typedef void* cl_platform_id;
typedef void* cl_device_id;

typedef cl_int (*PFN_clGetPlatformIDs)(
        cl_uint,
        cl_platform_id*,
        cl_uint*
);

typedef cl_int (*PFN_clGetPlatformInfo)(
        cl_platform_id,
        cl_uint,
        size_t,
        void*,
        size_t*
);

typedef cl_int (*PFN_clGetDeviceIDs)(
        cl_platform_id,
        cl_ulong,
        cl_uint,
        cl_device_id*,
        cl_uint*
);

typedef cl_int (*PFN_clGetDeviceInfo)(
        cl_device_id,
        cl_uint,
        size_t,
        void*,
        size_t*
);

#define CL_SUCCESS 0
#define CL_DEVICE_TYPE_ALL 0xFFFFFFFFULL

#define CL_PLATFORM_PROFILE    0x0900
#define CL_PLATFORM_VERSION    0x0901
#define CL_PLATFORM_NAME       0x0902
#define CL_PLATFORM_VENDOR     0x0903

#define CL_DEVICE_NAME         0x102B
#define CL_DEVICE_VENDOR       0x102C
#define CL_DEVICE_VERSION      0x102F
#define CL_DRIVER_VERSION      0x102D

static std::string jsonEscape(const std::string& input) {
    std::string out;

    for (char c : input) {
        switch (c) {
            case '"':
                out += "\\\"";
                break;

            case '\\':
                out += "\\\\";
                break;

            case '\n':
                out += "\\n";
                break;

            case '\r':
                out += "\\r";
                break;

            case '\t':
                out += "\\t";
                break;

            default:
                out += c;
        }
    }

    return out;
}

static std::string getPlatformString(
        PFN_clGetPlatformInfo fn,
        cl_platform_id platform,
        cl_uint param
) {
    size_t size = 0;

    if (fn(
            platform,
            param,
            0,
            nullptr,
            &size
    ) != CL_SUCCESS || size == 0) {
        return "";
    }

    std::vector<char> buffer(size);

    if (fn(
            platform,
            param,
            size,
            buffer.data(),
            nullptr
    ) != CL_SUCCESS) {
        return "";
    }

    return std::string(buffer.data());
}

static std::string getDeviceString(
        PFN_clGetDeviceInfo fn,
        cl_device_id device,
        cl_uint param
) {
    size_t size = 0;

    if (fn(
            device,
            param,
            0,
            nullptr,
            &size
    ) != CL_SUCCESS || size == 0) {
        return "";
    }

    std::vector<char> buffer(size);

    if (fn(
            device,
            param,
            size,
            buffer.data(),
            nullptr
    ) != CL_SUCCESS) {
        return "";
    }

    return std::string(buffer.data());
}

static std::string testLibrary(const char* libraryName) {

    std::ostringstream result;

    result << "{";
    result << "\"library\":\""
           << jsonEscape(libraryName)
           << "\",";

    void* handle = dlopen(
            libraryName,
            RTLD_NOW | RTLD_LOCAL
    );

    if (!handle) {

        const char* error = dlerror();

        result << "\"loaded\":false,";
        result << "\"openclSymbols\":false,";
        result << "\"error\":\""
               << jsonEscape(
                       error ? error : "unknown dlopen error"
               )
               << "\"";
        result << "}";

        return result.str();
    }

    result << "\"loaded\":true,";

    dlerror();

    auto clGetPlatformIDs =
            reinterpret_cast<PFN_clGetPlatformIDs>(
                    dlsym(handle, "clGetPlatformIDs")
            );

    auto clGetPlatformInfo =
            reinterpret_cast<PFN_clGetPlatformInfo>(
                    dlsym(handle, "clGetPlatformInfo")
            );

    auto clGetDeviceIDs =
            reinterpret_cast<PFN_clGetDeviceIDs>(
                    dlsym(handle, "clGetDeviceIDs")
            );

    auto clGetDeviceInfo =
            reinterpret_cast<PFN_clGetDeviceInfo>(
                    dlsym(handle, "clGetDeviceInfo")
            );

    bool symbols =
            clGetPlatformIDs &&
            clGetPlatformInfo &&
            clGetDeviceIDs &&
            clGetDeviceInfo;

    result << "\"openclSymbols\":"
           << (symbols ? "true" : "false")
           << ",";

    if (!symbols) {

        result << "\"status\":\"OPENCL_SYMBOLS_NOT_FOUND\"";

        result << "}";

        dlclose(handle);

        return result.str();
    }

    cl_uint platformCount = 0;

    cl_int platformResult =
            clGetPlatformIDs(
                    0,
                    nullptr,
                    &platformCount
            );

    result << "\"platformQueryResult\":"
           << platformResult
           << ",";

    result << "\"platformCount\":"
           << platformCount
           << ",";

    if (platformResult != CL_SUCCESS ||
        platformCount == 0) {

        result << "\"status\":\"NO_OPENCL_PLATFORM\"";

        result << "}";

        dlclose(handle);

        return result.str();
    }

    std::vector<cl_platform_id> platforms(
            platformCount
    );

    platformResult =
            clGetPlatformIDs(
                    platformCount,
                    platforms.data(),
                    nullptr
            );

    if (platformResult != CL_SUCCESS) {

        result << "\"status\":\"PLATFORM_ENUMERATION_FAILED\"";

        result << "}";

        dlclose(handle);

        return result.str();
    }

    result << "\"platforms\":[";

    for (cl_uint p = 0; p < platformCount; p++) {

        if (p > 0) {
            result << ",";
        }

        std::string platformName =
                getPlatformString(
                        clGetPlatformInfo,
                        platforms[p],
                        CL_PLATFORM_NAME
                );

        std::string platformVendor =
                getPlatformString(
                        clGetPlatformInfo,
                        platforms[p],
                        CL_PLATFORM_VENDOR
                );

        std::string platformVersion =
                getPlatformString(
                        clGetPlatformInfo,
                        platforms[p],
                        CL_PLATFORM_VERSION
                );

        result << "{";

        result << "\"name\":\""
               << jsonEscape(platformName)
               << "\",";

        result << "\"vendor\":\""
               << jsonEscape(platformVendor)
               << "\",";

        result << "\"version\":\""
               << jsonEscape(platformVersion)
               << "\",";

        cl_uint deviceCount = 0;

        cl_int deviceResult =
                clGetDeviceIDs(
                        platforms[p],
                        CL_DEVICE_TYPE_ALL,
                        0,
                        nullptr,
                        &deviceCount
                );

        result << "\"deviceQueryResult\":"
               << deviceResult
               << ",";

        result << "\"deviceCount\":"
               << deviceCount;

        if (deviceResult == CL_SUCCESS &&
            deviceCount > 0) {

            std::vector<cl_device_id> devices(
                    deviceCount
            );

            deviceResult =
                    clGetDeviceIDs(
                            platforms[p],
                            CL_DEVICE_TYPE_ALL,
                            deviceCount,
                            devices.data(),
                            nullptr
                    );

            result << ",\"devices\":[";

            if (deviceResult == CL_SUCCESS) {

                for (cl_uint d = 0; d < deviceCount; d++) {

                    if (d > 0) {
                        result << ",";
                    }

                    std::string deviceName =
                            getDeviceString(
                                    clGetDeviceInfo,
                                    devices[d],
                                    CL_DEVICE_NAME
                            );

                    std::string deviceVendor =
                            getDeviceString(
                                    clGetDeviceInfo,
                                    devices[d],
                                    CL_DEVICE_VENDOR
                            );

                    std::string deviceVersion =
                            getDeviceString(
                                    clGetDeviceInfo,
                                    devices[d],
                                    CL_DEVICE_VERSION
                            );

                    std::string driverVersion =
                            getDeviceString(
                                    clGetDeviceInfo,
                                    devices[d],
                                    CL_DRIVER_VERSION
                            );

                    result << "{";

                    result << "\"name\":\""
                           << jsonEscape(deviceName)
                           << "\",";

                    result << "\"vendor\":\""
                           << jsonEscape(deviceVendor)
                           << "\",";

                    result << "\"version\":\""
                           << jsonEscape(deviceVersion)
                           << "\",";

                    result << "\"driver\":\""
                           << jsonEscape(driverVersion)
                           << "\"";

                    result << "}";
                }
            }

            result << "]";
        }

        result << "}";
    }

    result << "],";

    result << "\"status\":\"OPENCL_WORKING\"";

    result << "}";

    dlclose(handle);

    return result.str();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_expo_modules_hybridffmpeg_HybridFfmpegModule_nativeOpenClDiagnostic(
        JNIEnv* env,
        jobject /* thiz */
) {

    const char* candidates[] = {
            "libOpenCL.so",
            "libOpenCL.so.1",
            "libGLES_mali.so",
            "libmali.so",
            "libOpenCL-pixel.so"
    };

    const int candidateCount =
            sizeof(candidates) /
            sizeof(candidates[0]);

    std::ostringstream output;

    output << "{";
    output << "\"status\":\"DIAGNOSTIC_COMPLETE\",";
    output << "\"candidateCount\":"
           << candidateCount
           << ",";
    output << "\"candidates\":[";

    for (int i = 0; i < candidateCount; i++) {

        if (i > 0) {
            output << ",";
        }

        output << testLibrary(
                candidates[i]
        );
    }

    output << "]";
    output << "}";

    return env->NewStringUTF(
            output.str().c_str()
    );
}
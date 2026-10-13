#include <jni.h>
#include <vulkan/vulkan.h>
#include <chrono>
#include <cstdint>
#include <sstream>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_com_matheus_arceusnative_MainActivity_nativeDiagnostics(JNIEnv* env, jobject) {
    std::ostringstream out;
    out << "Nucleo C++ nativo / Vulkan\n";
#if defined(__aarch64__)
    out << "Arquitetura: ARM64\n";
#else
    out << "Arquitetura: nao ARM64\n";
#endif
    uint64_t checksum = 0;
    auto start = std::chrono::steady_clock::now();
    for (uint64_t i = 0; i < 1000000; ++i) checksum += ((i * 17) ^ (i >> 3));
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    out << "Teste ARM64: OK (" << ms << " ms)\nChecksum: " << checksum << "\n";
    uint32_t version = VK_API_VERSION_1_0;
    auto res = vkEnumerateInstanceVersion(&version);
    if (res == VK_SUCCESS) {
        out << "Vulkan loader: " << VK_VERSION_MAJOR(version) << "." << VK_VERSION_MINOR(version) << "\n";
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "Arceus Native Lab";
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        info.pApplicationInfo = &app;
        VkInstance instance = VK_NULL_HANDLE;
        res = vkCreateInstance(&info, nullptr, &instance);
        if (res == VK_SUCCESS) {
            uint32_t count = 0;
            if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count) {
                std::vector<VkPhysicalDevice> devices(count);
                if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
                    for (auto device : devices) {
                        VkPhysicalDeviceProperties props{};
                        vkGetPhysicalDeviceProperties(device, &props);
                        out << "GPU: " << props.deviceName << "\n";
                        out << "Vulkan GPU: " << VK_VERSION_MAJOR(props.apiVersion) << "." << VK_VERSION_MINOR(props.apiVersion) << "\n";
                    }
                }
            } else out << "GPU Vulkan nao encontrada\n";
            vkDestroyInstance(instance, nullptr);
        } else out << "Falha vkCreateInstance: " << res << "\n";
    } else out << "Vulkan indisponivel: " << res << "\n";
    out << "Driver ativo: sistema\nTurnip externo: nao implementado\nExecucao Switch: nao implementada";
    return env->NewStringUTF(out.str().c_str());
}

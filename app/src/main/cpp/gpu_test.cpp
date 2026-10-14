#include <jni.h>
#include <vulkan/vulkan.h>
#include <sstream>
#include <cstdint>

static std::string runGpuTest() {
    std::ostringstream log;
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkResult result = VK_SUCCESS;
    const char* stage = "inicio";
    bool passed = false;
    do {
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "Arceus GPU test";
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo = &app;
        stage = "vkCreateInstance";
        result = vkCreateInstance(&ici, nullptr, &instance);
        if (result != VK_SUCCESS) break;
        uint32_t deviceCount = 0;
        stage = "vkEnumeratePhysicalDevices";
        result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
        if (result != VK_SUCCESS || !deviceCount) break;
        // No heap allocation required for the typical single-GPU Android device.
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        {
            VkPhysicalDevice devices[16]{};
            if (deviceCount > 16) deviceCount = 16;
            result = vkEnumeratePhysicalDevices(instance, &deviceCount, devices);
            if (result != VK_SUCCESS || !deviceCount) break;
            physical = devices[0];
        }
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physical, &properties);
        log << "GPU: " << properties.deviceName << "\n";
        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &familyCount, nullptr);
        if (!familyCount) { stage = "sem filas Vulkan"; result = VK_ERROR_INITIALIZATION_FAILED; break; }
        VkQueueFamilyProperties families[32]{};
        if (familyCount > 32) familyCount = 32;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &familyCount, families);
        uint32_t family = UINT32_MAX;
        for (uint32_t i = 0; i < familyCount; ++i) {
            if ((families[i].queueFlags & VK_QUEUE_TRANSFER_BIT) && families[i].queueCount) { family = i; break; }
        }
        if (family == UINT32_MAX) { stage = "sem fila de transferencia"; result = VK_ERROR_INITIALIZATION_FAILED; break; }
        float priority = 1.0f;
        VkDeviceQueueCreateInfo qci{};
        qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci.queueFamilyIndex = family;
        qci.queueCount = 1;
        qci.pQueuePriorities = &priority;
        VkDeviceCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        stage = "vkCreateDevice";
        result = vkCreateDevice(physical, &dci, nullptr, &device);
        if (result != VK_SUCCESS) break;
        VkQueue queue = VK_NULL_HANDLE;
        vkGetDeviceQueue(device, family, 0, &queue);
        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = 4096;
        bci.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        stage = "vkCreateBuffer";
        result = vkCreateBuffer(device, &bci, nullptr, &buffer);
        if (result != VK_SUCCESS) break;
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, buffer, &req);
        VkPhysicalDeviceMemoryProperties memProps{};
        vkGetPhysicalDeviceMemoryProperties(physical, &memProps);
        uint32_t memType = UINT32_MAX;
        for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
            auto flags = memProps.memoryTypes[i].propertyFlags;
            if ((req.memoryTypeBits & (1u << i)) &&
                (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                    (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                memType = i; break;
            }
        }
        if (memType == UINT32_MAX) { stage = "sem memoria host-coherent"; result = VK_ERROR_FEATURE_NOT_PRESENT; break; }
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = memType;
        stage = "vkAllocateMemory";
        result = vkAllocateMemory(device, &mai, nullptr, &memory);
        if (result != VK_SUCCESS) break;
        stage = "vkBindBufferMemory";
        result = vkBindBufferMemory(device, buffer, memory, 0);
        if (result != VK_SUCCESS) break;
        VkCommandPoolCreateInfo pci{};
        pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pci.queueFamilyIndex = family;
        stage = "vkCreateCommandPool";
        result = vkCreateCommandPool(device, &pci, nullptr, &pool);
        if (result != VK_SUCCESS) break;
        VkCommandBufferAllocateInfo cai{};
        cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cai.commandPool = pool;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = 1;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        stage = "vkAllocateCommandBuffers";
        result = vkAllocateCommandBuffers(device, &cai, &cmd);
        if (result != VK_SUCCESS) break;
        VkCommandBufferBeginInfo cbi{};
        cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        stage = "vkBeginCommandBuffer";
        result = vkBeginCommandBuffer(cmd, &cbi);
        if (result != VK_SUCCESS) break;
        constexpr uint32_t expected = 0xA7C3E042u;
        vkCmdFillBuffer(cmd, buffer, 0, 4096, expected);
        stage = "vkEndCommandBuffer";
        result = vkEndCommandBuffer(cmd);
        if (result != VK_SUCCESS) break;
        VkFenceCreateInfo fci{};
        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        stage = "vkCreateFence";
        result = vkCreateFence(device, &fci, nullptr, &fence);
        if (result != VK_SUCCESS) break;
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        stage = "vkQueueSubmit";
        result = vkQueueSubmit(queue, 1, &si, fence);
        if (result != VK_SUCCESS) break;
        stage = "vkWaitForFences";
        result = vkWaitForFences(device, 1, &fence, VK_TRUE, 5000000000ULL);
        if (result != VK_SUCCESS) break;
        void* mapped = nullptr;
        stage = "vkMapMemory";
        result = vkMapMemory(device, memory, 0, 4096, 0, &mapped);
        if (result != VK_SUCCESS) break;
        const auto* values = static_cast<const uint32_t*>(mapped);
        passed = true;
        for (uint32_t i = 0; i < 1024; ++i) {
            if (values[i] != expected) { passed = false; break; }
        }
        vkUnmapMemory(device, memory);
        stage = "validacao dos 4096 bytes";
        if (!passed) result = VK_ERROR_UNKNOWN;
    } while (false);
    if (device) vkDeviceWaitIdle(device);
    if (fence) vkDestroyFence(device, fence, nullptr);
    if (pool) vkDestroyCommandPool(device, pool, nullptr);
    if (buffer) vkDestroyBuffer(device, buffer, nullptr);
    if (memory) vkFreeMemory(device, memory, nullptr);
    if (device) vkDestroyDevice(device, nullptr);
    if (instance) vkDestroyInstance(instance, nullptr);
    if (passed) log << "Teste GPU Vulkan: OK\nComando: vkCmdFillBuffer\nDados conferidos: 4096 bytes\n";
    else log << "Teste GPU Vulkan: FALHOU\nEtapa: " << stage << "\nVkResult: " << result << "\n";
    log << "Renderizacao na tela: ainda nao implementada\n";
    log << "Turnip externo: ainda nao implementado\n";
    log << "Execucao Switch: ainda nao implementada";
    return log.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_matheus_arceusnative_MainActivity_nativeGpuTest(JNIEnv* env, jobject) {
    const auto message = runGpuTest();
    return env->NewStringUTF(message.c_str());
}

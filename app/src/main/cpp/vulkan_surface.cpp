#define VK_USE_PLATFORM_ANDROID_KHR
#include <jni.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vector>
#include <string>
#include <sstream>
#include <algorithm>
#include <chrono>
#include <cmath>

static std::string renderOnce(JNIEnv* env, jobject surface, float phase) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return "Falha: superficie Android indisponivel";
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR vkSurface = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE, finished = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkResult result = VK_SUCCESS;
    std::string stage = "inicio";
    std::ostringstream report;
    bool success = false;
    do {
        const char* instanceExtensions[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Arceus Vulkan Surface";
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo = &app;
        ici.enabledExtensionCount = 2;
        ici.ppEnabledExtensionNames = instanceExtensions;
        stage = "vkCreateInstance";
        result = vkCreateInstance(&ici, nullptr, &instance);
        if (result != VK_SUCCESS) break;
        VkAndroidSurfaceCreateInfoKHR asci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        asci.window = window;
        stage = "vkCreateAndroidSurfaceKHR";
        result = vkCreateAndroidSurfaceKHR(instance, &asci, nullptr, &vkSurface);
        if (result != VK_SUCCESS) break;
        uint32_t deviceCount = 0;
        stage = "vkEnumeratePhysicalDevices";
        result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
        if (result != VK_SUCCESS || deviceCount == 0) break;
        std::vector<VkPhysicalDevice> physicals(deviceCount);
        result = vkEnumeratePhysicalDevices(instance, &deviceCount, physicals.data());
        if (result != VK_SUCCESS) break;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        uint32_t family = UINT32_MAX;
        for (auto p : physicals) {
            uint32_t count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(p, &count, nullptr);
            std::vector<VkQueueFamilyProperties> props(count);
            vkGetPhysicalDeviceQueueFamilyProperties(p, &count, props.data());
            for (uint32_t i = 0; i < count; ++i) {
                VkBool32 present = VK_FALSE;
                if (vkGetPhysicalDeviceSurfaceSupportKHR(p, i, vkSurface, &present) == VK_SUCCESS &&
                    present && props[i].queueCount && (props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                    physical = p; family = i; break;
                }
            }
            if (physical) break;
        }
        if (!physical) { stage = "sem fila grafica com apresentacao"; result = VK_ERROR_INITIALIZATION_FAILED; break; }
        float priority = 1.0f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = family; qci.queueCount = 1; qci.pQueuePriorities = &priority;
        const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
        dci.enabledExtensionCount = 1; dci.ppEnabledExtensionNames = deviceExtensions;
        stage = "vkCreateDevice";
        result = vkCreateDevice(physical, &dci, nullptr, &device);
        if (result != VK_SUCCESS) break;
        VkQueue queue = VK_NULL_HANDLE;
        vkGetDeviceQueue(device, family, 0, &queue);
        VkSurfaceCapabilitiesKHR caps{};
        stage = "vkGetPhysicalDeviceSurfaceCapabilitiesKHR";
        result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, vkSurface, &caps);
        if (result != VK_SUCCESS) break;
        uint32_t formatCount = 0;
        result = vkGetPhysicalDeviceSurfaceFormatsKHR(physical, vkSurface, &formatCount, nullptr);
        if (result != VK_SUCCESS || formatCount == 0) { stage = "formatos de superficie"; break; }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        result = vkGetPhysicalDeviceSurfaceFormatsKHR(physical, vkSurface, &formatCount, formats.data());
        if (result != VK_SUCCESS) break;
        VkSurfaceFormatKHR chosen = formats[0];
        for (auto f : formats) if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) { chosen = f; break; }
        if (chosen.format == VK_FORMAT_UNDEFINED) chosen.format = VK_FORMAT_R8G8B8A8_UNORM;
        if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
            stage = "swapchain sem suporte TRANSFER_DST"; result = VK_ERROR_FEATURE_NOT_PRESENT; break;
        }
        VkExtent2D extent = caps.currentExtent;
        if (extent.width == UINT32_MAX) {
            extent.width = std::clamp(static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window))), caps.minImageExtent.width, caps.maxImageExtent.width);
            extent.height = std::clamp(static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window))), caps.minImageExtent.height, caps.maxImageExtent.height);
        }
        if (!extent.width || !extent.height) { stage = "dimensoes invalidas"; result = VK_ERROR_INITIALIZATION_FAILED; break; }
        uint32_t imagesWanted = caps.minImageCount + 1;
        if (caps.maxImageCount && imagesWanted > caps.maxImageCount) imagesWanted = caps.maxImageCount;
        VkSurfaceTransformFlagBitsKHR transform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        if (!(caps.supportedCompositeAlpha & alpha)) {
            for (auto candidate : {VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR}) {
                if (caps.supportedCompositeAlpha & candidate) { alpha = candidate; break; }
            }
        }
        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface = vkSurface; sci.minImageCount = imagesWanted;
        sci.imageFormat = chosen.format; sci.imageColorSpace = chosen.colorSpace;
        sci.imageExtent = extent; sci.imageArrayLayers = 1;
        sci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform = transform; sci.compositeAlpha = alpha;
        sci.presentMode = VK_PRESENT_MODE_FIFO_KHR; sci.clipped = VK_TRUE;
        stage = "vkCreateSwapchainKHR";
        result = vkCreateSwapchainKHR(device, &sci, nullptr, &swapchain);
        if (result != VK_SUCCESS) break;
        uint32_t imageCount = 0;
        result = vkGetSwapchainImagesKHR(device, swapchain, &imageCount, nullptr);
        if (result != VK_SUCCESS || !imageCount) { stage = "imagens swapchain"; break; }
        std::vector<VkImage> images(imageCount);
        result = vkGetSwapchainImagesKHR(device, swapchain, &imageCount, images.data());
        if (result != VK_SUCCESS) break;
        VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        stage = "vkCreateSemaphore";
        result = vkCreateSemaphore(device, &semInfo, nullptr, &acquired);
        if (result != VK_SUCCESS) break;
        result = vkCreateSemaphore(device, &semInfo, nullptr, &finished);
        if (result != VK_SUCCESS) break;
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        result = vkCreateFence(device, &fenceInfo, nullptr, &fence);
        if (result != VK_SUCCESS) break;
        uint32_t imageIndex = 0;
        stage = "vkAcquireNextImageKHR";
        result = vkAcquireNextImageKHR(device, swapchain, 5000000000ULL, acquired, VK_NULL_HANDLE, &imageIndex);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) break;
        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pci.queueFamilyIndex = family;
        stage = "vkCreateCommandPool";
        result = vkCreateCommandPool(device, &pci, nullptr, &pool);
        if (result != VK_SUCCESS) break;
        VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        alloc.commandPool = pool; alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; alloc.commandBufferCount = 1;
        result = vkAllocateCommandBuffers(device, &alloc, &command);
        if (result != VK_SUCCESS) { stage = "vkAllocateCommandBuffers"; break; }
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        result = vkBeginCommandBuffer(command, &begin);
        if (result != VK_SUCCESS) { stage = "vkBeginCommandBuffer"; break; }
        VkImageMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        toTransfer.srcAccessMask = 0; toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.image = images[imageIndex];
        toTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        toTransfer.subresourceRange.levelCount = 1; toTransfer.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransfer);
        VkClearColorValue color{};
        color.float32[0] = 0.12f + 0.30f * (0.5f + 0.5f * std::sin(phase)); color.float32[1] = 0.20f + 0.45f * (0.5f + 0.5f * std::sin(phase + 2.09f)); color.float32[2] = 0.25f + 0.65f * (0.5f + 0.5f * std::sin(phase + 4.18f)); color.float32[3] = 1.0f;
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; range.levelCount = 1; range.layerCount = 1;
        vkCmdClearColorImage(command, images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &range);
        VkImageMemoryBarrier toPresent{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        toPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toPresent.dstAccessMask = 0;
        toPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        toPresent.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toPresent.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toPresent.image = images[imageIndex];
        toPresent.subresourceRange = range;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &toPresent);
        stage = "vkEndCommandBuffer";
        result = vkEndCommandBuffer(command);
        if (result != VK_SUCCESS) break;
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1; submit.pWaitSemaphores = &acquired; submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
        submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &finished;
        stage = "vkQueueSubmit";
        result = vkQueueSubmit(queue, 1, &submit, fence);
        if (result != VK_SUCCESS) break;
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = 1; present.pWaitSemaphores = &finished;
        present.swapchainCount = 1; present.pSwapchains = &swapchain; present.pImageIndices = &imageIndex;
        stage = "vkQueuePresentKHR";
        result = vkQueuePresentKHR(queue, &present);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) break;
        stage = "vkWaitForFences";
        result = vkWaitForFences(device, 1, &fence, VK_TRUE, 5000000000ULL);
        if (result != VK_SUCCESS) break;
        success = true;
        report << "Vulkan na tela: OK (quadro azul enviado)\n";
        report << "Resolucao: " << extent.width << "x" << extent.height << "\n";
        report << "Fila grafica/present: " << family << "\n";
    } while(false);
    if (device) vkDeviceWaitIdle(device);
    if (fence) vkDestroyFence(device, fence, nullptr);
    if (pool) vkDestroyCommandPool(device, pool, nullptr);
    if (acquired) vkDestroySemaphore(device, acquired, nullptr);
    if (finished) vkDestroySemaphore(device, finished, nullptr);
    if (swapchain) vkDestroySwapchainKHR(device, swapchain, nullptr);
    if (device) vkDestroyDevice(device, nullptr);
    if (vkSurface) vkDestroySurfaceKHR(instance, vkSurface, nullptr);
    if (instance) vkDestroyInstance(instance, nullptr);
    ANativeWindow_release(window);
    if (!success) report << "Vulkan na tela: FALHOU\nEtapa: " << stage << "\nVkResult: " << result;
    return report.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_matheus_arceusnative_MainActivity_nativeRenderSurface(JNIEnv* env, jobject, jobject surface) {
    const std::string report = renderOnce(env, surface, 0.0f);
    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_matheus_arceusnative_MainActivity_nativeAnimationTest(JNIEnv* env, jobject, jobject surface) {
    const auto start = std::chrono::steady_clock::now();
    std::string last;
    int frames = 0;
    for (int i = 0; i < 30; ++i) {
        last = renderOnce(env, surface, i * 0.22f);
        if (last.find("Vulkan na tela: OK") == std::string::npos) break;
        ++frames;
    }
    const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::ostringstream out;
    out << "Animacao Vulkan experimental\n";
    out << "Quadros apresentados: " << frames << "/30\n";
    out << "Tempo: " << elapsed << " s\n";
    if (elapsed > 0) out << "Taxa media (inclui recriacao Vulkan): " << (frames / elapsed) << " FPS\n";
    out << "Nota: teste de cores, nao renderizacao 3D.\n" << last;
    return env->NewStringUTF(out.str().c_str());
}

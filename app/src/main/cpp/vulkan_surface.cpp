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
#include "triangle_shaders.h"
#include <chrono>
#include <cmath>
#include "triangle_shaders.h"
#include <chrono>
#include <cmath>
#include "triangle_shaders.h"

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


// Vulkan 1.0 triangle renderer. Uses the same Android surface/swapchain flow.
static std::string triangleOnce(JNIEnv* env, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return "Falha: superficie indisponivel";
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surfaceKHR = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkRenderPass pass = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkShaderModule vert = VK_NULL_HANDLE, frag = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE, rendered = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> framebuffers;
    VkResult rc = VK_SUCCESS;
    std::string stage = "inicio";
    VkExtent2D extent{};
    bool ok = false;
    do {
        const char* iext[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO}; ai.apiVersion=VK_API_VERSION_1_0;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo=&ai; ici.enabledExtensionCount=2; ici.ppEnabledExtensionNames=iext;
        stage="vkCreateInstance"; rc=vkCreateInstance(&ici,nullptr,&instance); if(rc)break;
        VkAndroidSurfaceCreateInfoKHR asc{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR}; asc.window=window;
        stage="vkCreateAndroidSurfaceKHR"; rc=vkCreateAndroidSurfaceKHR(instance,&asc,nullptr,&surfaceKHR); if(rc)break;
        uint32_t n=0; rc=vkEnumeratePhysicalDevices(instance,&n,nullptr); if(rc||!n)break;
        std::vector<VkPhysicalDevice> gpus(n); rc=vkEnumeratePhysicalDevices(instance,&n,gpus.data()); if(rc)break;
        VkPhysicalDevice gpu=VK_NULL_HANDLE; uint32_t family=UINT32_MAX;
        for(auto candidate:gpus) {
            uint32_t count=0; vkGetPhysicalDeviceQueueFamilyProperties(candidate,&count,nullptr);
            std::vector<VkQueueFamilyProperties> q(count); vkGetPhysicalDeviceQueueFamilyProperties(candidate,&count,q.data());
            for(uint32_t i=0;i<count;i++) { VkBool32 present=VK_FALSE;
                if(q[i].queueCount && (q[i].queueFlags&VK_QUEUE_GRAPHICS_BIT) &&
                   vkGetPhysicalDeviceSurfaceSupportKHR(candidate,i,surfaceKHR,&present)==VK_SUCCESS && present) {
                    gpu=candidate;family=i;break;
                }
            }
            if(gpu)break;
        }
        if(!gpu){stage="fila grafica/present";rc=VK_ERROR_INITIALIZATION_FAILED;break;}
        float priority=1.0f; VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex=family;qci.queueCount=1;qci.pQueuePriorities=&priority;
        const char* dext[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;dci.enabledExtensionCount=1;dci.ppEnabledExtensionNames=dext;
        stage="vkCreateDevice";rc=vkCreateDevice(gpu,&dci,nullptr,&device);if(rc)break;
        VkQueue queue=VK_NULL_HANDLE;vkGetDeviceQueue(device,family,0,&queue);
        VkSurfaceCapabilitiesKHR caps{};stage="surface caps";
        rc=vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu,surfaceKHR,&caps);if(rc)break;
        uint32_t nf=0;rc=vkGetPhysicalDeviceSurfaceFormatsKHR(gpu,surfaceKHR,&nf,nullptr);if(rc||!nf)break;
        std::vector<VkSurfaceFormatKHR> formats(nf);rc=vkGetPhysicalDeviceSurfaceFormatsKHR(gpu,surfaceKHR,&nf,formats.data());if(rc)break;
        VkSurfaceFormatKHR fmt=formats[0];
        for(auto f:formats)if(f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM){fmt=f;break;}
        if(fmt.format==VK_FORMAT_UNDEFINED)fmt.format=VK_FORMAT_R8G8B8A8_UNORM;
        if(!(caps.supportedUsageFlags&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {stage="COLOR_ATTACHMENT indisponivel";rc=VK_ERROR_FEATURE_NOT_PRESENT;break;}
        extent=caps.currentExtent;
        if(extent.width==UINT32_MAX){extent.width=std::clamp((uint32_t)std::max(1,ANativeWindow_getWidth(window)),caps.minImageExtent.width,caps.maxImageExtent.width);
            extent.height=std::clamp((uint32_t)std::max(1,ANativeWindow_getHeight(window)),caps.minImageExtent.height,caps.maxImageExtent.height);}
        uint32_t wanted=caps.minImageCount+1;if(caps.maxImageCount)wanted=std::min(wanted,caps.maxImageCount);
        VkCompositeAlphaFlagBitsKHR alpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        if(!(caps.supportedCompositeAlpha&alpha)){
            for(auto a:{VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR})
                if(caps.supportedCompositeAlpha&a){alpha=a;break;}
        }
        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface=surfaceKHR;sci.minImageCount=wanted;sci.imageFormat=fmt.format;sci.imageColorSpace=fmt.colorSpace;
        sci.imageExtent=extent;sci.imageArrayLayers=1;sci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        sci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;sci.preTransform=caps.currentTransform;
        sci.compositeAlpha=alpha;sci.presentMode=VK_PRESENT_MODE_FIFO_KHR;sci.clipped=VK_TRUE;
        stage="vkCreateSwapchainKHR";rc=vkCreateSwapchainKHR(device,&sci,nullptr,&swapchain);if(rc)break;
        uint32_t imageCount=0;rc=vkGetSwapchainImagesKHR(device,swapchain,&imageCount,nullptr);if(rc||!imageCount)break;
        std::vector<VkImage> images(imageCount);rc=vkGetSwapchainImagesKHR(device,swapchain,&imageCount,images.data());if(rc)break;
        for(auto image:images){VkImageViewCreateInfo iv{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            iv.image=image;iv.viewType=VK_IMAGE_VIEW_TYPE_2D;iv.format=fmt.format;
            iv.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;iv.subresourceRange.levelCount=1;iv.subresourceRange.layerCount=1;
            VkImageView view=VK_NULL_HANDLE;stage="vkCreateImageView";rc=vkCreateImageView(device,&iv,nullptr,&view);if(rc)break;views.push_back(view);}
        if(rc)break;
        VkAttachmentDescription attachment{};attachment.format=fmt.format;attachment.samples=VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;attachment.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
        attachment.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;attachment.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount=1;sub.pColorAttachments=&ref;
        VkSubpassDependency dep{};dep.srcSubpass=VK_SUBPASS_EXTERNAL;dep.dstSubpass=0;
        dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rp.attachmentCount=1;rp.pAttachments=&attachment;rp.subpassCount=1;rp.pSubpasses=&sub;
        rp.dependencyCount=1;rp.pDependencies=&dep;
        stage="vkCreateRenderPass";rc=vkCreateRenderPass(device,&rp,nullptr,&pass);if(rc)break;
        for(auto view:views){VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fi.renderPass=pass;fi.attachmentCount=1;fi.pAttachments=&view;fi.width=extent.width;fi.height=extent.height;fi.layers=1;
            VkFramebuffer fb=VK_NULL_HANDLE;stage="vkCreateFramebuffer";rc=vkCreateFramebuffer(device,&fi,nullptr,&fb);if(rc)break;framebuffers.push_back(fb);}
        if(rc)break;
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        sm.codeSize=sizeof(arceus_triangle_vert);sm.pCode=arceus_triangle_vert;
        stage="vertex shader";rc=vkCreateShaderModule(device,&sm,nullptr,&vert);if(rc)break;
        sm.codeSize=sizeof(arceus_triangle_frag);sm.pCode=arceus_triangle_frag;
        stage="fragment shader";rc=vkCreateShaderModule(device,&sm,nullptr,&frag);if(rc)break;
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module=vert;stages[0].pName="main";
        stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module=frag;stages[1].pName="main";
        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkViewport viewport{0,0,(float)extent.width,(float)extent.height,0,1};
        VkRect2D scissor{{0,0},extent};
        VkPipelineViewportStateCreateInfo vs{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vs.viewportCount=1;vs.pViewports=&viewport;vs.scissorCount=1;vs.pScissors=&scissor;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode=VK_POLYGON_MODE_FILL;rs.lineWidth=1.0f;rs.cullMode=VK_CULL_MODE_NONE;
        rs.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blend{};blend.colorWriteMask=VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo bs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};bs.attachmentCount=1;bs.pAttachments=&blend;
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        stage="vkCreatePipelineLayout";rc=vkCreatePipelineLayout(device,&pl,nullptr,&layout);if(rc)break;
        VkGraphicsPipelineCreateInfo gp{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        gp.stageCount=2;gp.pStages=stages;gp.pVertexInputState=&vi;gp.pInputAssemblyState=&ia;
        gp.pViewportState=&vs;gp.pRasterizationState=&rs;gp.pMultisampleState=&ms;
        gp.pColorBlendState=&bs;gp.layout=layout;gp.renderPass=pass;gp.subpass=0;
        stage="vkCreateGraphicsPipelines";rc=vkCreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&gp,nullptr,&pipeline);if(rc)break;
        VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        rc=vkCreateSemaphore(device,&si,nullptr,&acquired);if(rc)break;
        rc=vkCreateSemaphore(device,&si,nullptr,&rendered);if(rc)break;
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};rc=vkCreateFence(device,&fci,nullptr,&fence);if(rc)break;
        uint32_t index=0;stage="vkAcquireNextImageKHR";
        rc=vkAcquireNextImageKHR(device,swapchain,5000000000ULL,acquired,VK_NULL_HANDLE,&index);
        if(rc!=VK_SUCCESS&&rc!=VK_SUBOPTIMAL_KHR)break;
        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pci.queueFamilyIndex=family;
        stage="vkCreateCommandPool";rc=vkCreateCommandPool(device,&pci,nullptr,&pool);if(rc)break;
        VkCommandBuffer cb=VK_NULL_HANDLE;VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool=pool;cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;cai.commandBufferCount=1;
        rc=vkAllocateCommandBuffers(device,&cai,&cb);if(rc)break;
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};rc=vkBeginCommandBuffer(cb,&bi);if(rc)break;
        VkClearValue clear{};clear.color.float32[0]=0.05f;clear.color.float32[1]=0.08f;
        clear.color.float32[2]=0.15f;clear.color.float32[3]=1.0f;
        VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        begin.renderPass=pass;begin.framebuffer=framebuffers[index];begin.renderArea.extent=extent;
        begin.clearValueCount=1;begin.pClearValues=&clear;
        vkCmdBeginRenderPass(cb,&begin,VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
        vkCmdDraw(cb,3,1,0,0);
        vkCmdEndRenderPass(cb);
        stage="vkEndCommandBuffer";rc=vkEndCommandBuffer(cb);if(rc)break;
        VkPipelineStageFlags wait=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;
        submit.pWaitSemaphores=&acquired;submit.pWaitDstStageMask=&wait;
        submit.commandBufferCount=1;submit.pCommandBuffers=&cb;
        submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&rendered;
        stage="vkQueueSubmit";rc=vkQueueSubmit(queue,1,&submit,fence);if(rc)break;
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount=1;present.pWaitSemaphores=&rendered;
        present.swapchainCount=1;present.pSwapchains=&swapchain;present.pImageIndices=&index;
        stage="vkQueuePresentKHR";rc=vkQueuePresentKHR(queue,&present);
        if(rc!=VK_SUCCESS&&rc!=VK_SUBOPTIMAL_KHR)break;
        stage="vkWaitForFences";rc=vkWaitForFences(device,1,&fence,VK_TRUE,5000000000ULL);if(rc)break;
        ok=true;
    }while(false);
    if(device)vkDeviceWaitIdle(device);
    if(fence)vkDestroyFence(device,fence,nullptr);
    if(pool)vkDestroyCommandPool(device,pool,nullptr);
    if(rendered)vkDestroySemaphore(device,rendered,nullptr);
    if(acquired)vkDestroySemaphore(device,acquired,nullptr);
    if(pipeline)vkDestroyPipeline(device,pipeline,nullptr);
    if(layout)vkDestroyPipelineLayout(device,layout,nullptr);
    if(frag)vkDestroyShaderModule(device,frag,nullptr);
    if(vert)vkDestroyShaderModule(device,vert,nullptr);
    for(auto fb:framebuffers)vkDestroyFramebuffer(device,fb,nullptr);
    if(pass)vkDestroyRenderPass(device,pass,nullptr);
    for(auto v:views)vkDestroyImageView(device,v,nullptr);
    if(swapchain)vkDestroySwapchainKHR(device,swapchain,nullptr);
    if(device)vkDestroyDevice(device,nullptr);
    if(surfaceKHR)vkDestroySurfaceKHR(instance,surfaceKHR,nullptr);
    if(instance)vkDestroyInstance(instance,nullptr);
    ANativeWindow_release(window);
    std::ostringstream out;
    if(ok)out<<"Triangulo Vulkan: OK (vkCmdDraw, 3 vertices)\nResolucao: "<<extent.width<<"x"<<extent.height<<"\nPipeline: shaders SPIR-V";
    else out<<"Triangulo Vulkan: FALHOU\nEtapa: "<<stage<<"\nVkResult: "<<rc;
    return out.str();
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_matheus_arceusnative_MainActivity_nativeTriangleTest(JNIEnv* env,jobject,jobject surface){
    auto report=triangleOnce(env,surface);return env->NewStringUTF(report.c_str());
}

#include <jni.h>
#include <string.h>
#include <sys/system_properties.h>

static void copy_property(void *cookie, const char *name, const char *value, uint32_t serial) {
    (void)name;
    (void)serial;
    strlcpy((char *)cookie, value, PROP_VALUE_MAX);
}

/* Only public NDK property reads; no property writes, JNI reflection, or root code. */
JNIEXPORT jobjectArray JNICALL
Java_dev_experimental_rmgu_bootprobe_NativeProperties_read(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *names[] = {"init.svc.bootanim", "service.bootanim.exit", "sys.boot_completed"};
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, 3, string_class, NULL);
    if (result == NULL) return NULL;
    for (jsize i = 0; i < 3; ++i) {
        char value[PROP_VALUE_MAX] = {0};
        const prop_info *property = __system_property_find(names[i]);
        if (property != NULL) __system_property_read_callback(property, copy_property, value);
        jstring item = (*env)->NewStringUTF(env, value);
        if (item == NULL) return NULL;
        (*env)->SetObjectArrayElement(env, result, i, item);
        (*env)->DeleteLocalRef(env, item);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    return result;
}

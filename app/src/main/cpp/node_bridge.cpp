// JNI bridge: starts the embedded Node.js runtime (libnode.so) in-process.
//
// Contract with Kotlin (in.imayx.opendots.node.NodeRuntime):
//   nativeStartNode(cwd, scriptPath, args, env, logPath) -> exit code
// Blocks the calling thread until Node exits. node::Start may only run once
// per process; a second call returns START_ALREADY_RUNNING without doing anything.
#include <jni.h>

#include <node/node.h>

#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "OpenDotsNode"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_node_started{false};

namespace {

struct LogPumpArgs {
  int readFd;
  std::string logPath;
};

void *logPump(void *arg) {
  auto *p = static_cast<LogPumpArgs *>(arg);
  FILE *logFile = fopen(p->logPath.c_str(), "a");
  if (!logFile) {
    ALOGE("logPump: cannot open log file %s", p->logPath.c_str());
  }
  char buf[4096];
  std::string pending;
  ssize_t n;
  while ((n = read(p->readFd, buf, sizeof(buf))) > 0) {
    pending.append(buf, static_cast<size_t>(n));
    size_t pos;
    while ((pos = pending.find('\n')) != std::string::npos) {
      std::string line = pending.substr(0, pos);
      pending.erase(0, pos + 1);
      if (!line.empty() && line.back() == '\r') line.pop_back();
      ALOGI("%s", line.c_str());
      if (logFile) {
        fprintf(logFile, "%s\n", line.c_str());
        fflush(logFile);
      }
    }
  }
  if (!pending.empty()) {
    ALOGI("%s", pending.c_str());
    if (logFile) fprintf(logFile, "%s\n", pending.c_str());
  }
  if (logFile) fclose(logFile);
  close(p->readFd);
  delete p;
  return nullptr;
}

std::string jstringToString(JNIEnv *env, jstring jstr) {
  if (!jstr) return {};
  const char *chars = env->GetStringUTFChars(jstr, nullptr);
  std::string out(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(jstr, chars);
  return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_imayx_opendots_node_NodeRuntime_nativeStartNode(
    JNIEnv *env, jobject /*thiz*/, jstring jCwd, jstring jScriptPath,
    jobjectArray jArgs, jobjectArray jEnv, jstring jLogPath) {
  bool expected = false;
  if (!g_node_started.compare_exchange_strong(expected, true)) {
    ALOGE("nativeStartNode: node::Start already ran in this process; refusing second start");
    return -100;  // START_ALREADY_RUNNING
  }

  const std::string cwd = jstringToString(env, jCwd);
  const std::string scriptPath = jstringToString(env, jScriptPath);
  const std::string logPath = jstringToString(env, jLogPath);

  // Environment for the Node process.
  jsize envCount = jEnv ? env->GetArrayLength(jEnv) : 0;
  for (jsize i = 0; i < envCount; i++) {
    jstring entry = static_cast<jstring>(env->GetObjectArrayElement(jEnv, i));
    std::string kv = jstringToString(env, entry);
    env->DeleteLocalRef(entry);
    size_t eq = kv.find('=');
    if (eq != std::string::npos) {
      setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
    }
  }

  if (!cwd.empty() && chdir(cwd.c_str()) != 0) {
    ALOGE("nativeStartNode: chdir(%s) failed", cwd.c_str());
  }

  // Pump stdout/stderr to logcat + log file.
  int pipefd[2];
  if (pipe(pipefd) == 0) {
    auto *pargs = new LogPumpArgs{pipefd[0], logPath};
    pthread_t tid;
    if (pthread_create(&tid, nullptr, logPump, pargs) == 0) {
      pthread_detach(tid);
      dup2(pipefd[1], STDOUT_FILENO);
      dup2(pipefd[1], STDERR_FILENO);
      // Keep pipefd[1] open; the read end is owned by the pump thread.
    } else {
      delete pargs;
      close(pipefd[0]);
      close(pipefd[1]);
    }
  }

  // argv: node <script> [extra args...]
  std::vector<std::string> argStorage;
  argStorage.emplace_back("node");
  argStorage.push_back(scriptPath);
  jsize argCount = jArgs ? env->GetArrayLength(jArgs) : 0;
  for (jsize i = 0; i < argCount; i++) {
    jstring a = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
    argStorage.push_back(jstringToString(env, a));
    env->DeleteLocalRef(a);
  }
  std::vector<char *> argv;
  argv.reserve(argStorage.size());
  for (auto &s : argStorage) argv.push_back(s.data());

  ALOGI("nativeStartNode: starting node::Start (script=%s)", scriptPath.c_str());
  int exitCode = node::Start(static_cast<int>(argv.size()), argv.data());
  ALOGI("nativeStartNode: node::Start returned %d", exitCode);
  return exitCode;
}

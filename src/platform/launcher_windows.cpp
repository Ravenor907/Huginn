// Huginn.exe at the top of the Windows portable folder. Everything else (Qt, OpenSSL, the Visual C++
// runtime, plugins) lives in lib\ beside the real lib\Huginn.exe, so the folder shows one thing to
// open. This starts lib\Huginn.exe with the same arguments, working folder and console handles,
// waits for it and hands back its exit code. Plain Win32 and a static runtime: it needs no DLL of ours.
#include <windows.h>

#include <string>

namespace
{
void inheritable(DWORD which)
{
    const HANDLE h = GetStdHandle(which);
    if (h && h != INVALID_HANDLE_VALUE)
        SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
}
}

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int)
{
    std::wstring self(32768, L'\0');
    const DWORD n = GetModuleFileNameW(nullptr, self.data(), DWORD(self.size()));
    if (n == 0 || n >= self.size())
        return 1;
    self.resize(n);
    const std::wstring dir = self.substr(0, self.find_last_of(L"\\/") + 1);
    const std::wstring app = dir + L"lib\\Huginn.exe";

    // Our command line without our own program name, passed on unchanged.
    const wchar_t *rest = GetCommandLineW();
    if (*rest == L'"') {
        ++rest;
        while (*rest && *rest != L'"')
            ++rest;
        if (*rest)
            ++rest;
    } else {
        while (*rest && *rest != L' ' && *rest != L'\t')
            ++rest;
    }
    std::wstring line = L"\"" + app + L"\"" + rest;

    // The same console or pipes as ours, so command-line output (the CI self-tests) still shows.
    inheritable(STD_INPUT_HANDLE);
    inheritable(STD_OUTPUT_HANDLE);
    inheritable(STD_ERROR_HANDLE);
    STARTUPINFOW si{};
    si.cb = sizeof si;
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    si.hStdError = GetStdHandle(STD_ERROR_HANDLE);

    // If this launcher is closed, Huginn goes with it; what Huginn starts itself (the updater, a folder or file it opens) doesn't.
    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK;
        SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof limits);
    }

    // We were started by the person, so Huginn may come to the front.
    AllowSetForegroundWindow(ASFW_ANY);
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(app.c_str(), line.data(), nullptr, nullptr, TRUE, CREATE_SUSPENDED, nullptr, nullptr, &si, &pi)) {
        MessageBoxW(nullptr,
                    L"Huginn couldn't start: lib\\Huginn.exe is missing or blocked.\n\n"
                    L"Unzip Huginn-Windows.zip again and keep the lib folder next to Huginn.exe.",
                    L"Huginn", MB_ICONERROR | MB_OK);
        return 1;
    }
    if (job)
        AssignProcessToJobObject(job, pi.hProcess);
    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    if (job)
        CloseHandle(job);
    return int(code);
}

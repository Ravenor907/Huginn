cmake_minimum_required(VERSION 3.22)

# One dot only, read as a decimal: 0.3, 0.31, 0.4, 1.0.
set(HUGINN_VERSION "0.1" CACHE STRING "Version shown in the app")
project(huginn VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.6 REQUIRED COMPONENTS Core Gui Widgets Quick QuickControls2 Network DBus WebSockets Bluetooth Multimedia)
find_package(KF6Prison REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SDL2 REQUIRED IMPORTED_TARGET sdl2)

qt_standard_project_setup(REQUIRES 6.6)

qt_add_executable(huginn
    src/main.cpp
    src/settings.cpp src/settings.h
    src/system.cpp src/system.h
    src/gamepad.cpp src/gamepad.h
    src/transfer.cpp src/transfer.h
    src/updater.cpp src/updater.h
    src/localsend.cpp src/localsend.h
    src/chat.cpp src/chat.h
    src/direct.cpp src/direct.h
    src/chatcrypto.cpp src/chatcrypto.h
    src/chatwire.cpp src/chatwire.h
    src/ratchet.cpp src/ratchet.h
    src/vault.cpp src/vault.h
    src/photos.cpp src/photos.h
    src/voice.cpp src/voice.h
    src/schnorr.cpp src/schnorr.h
    src/nostr.cpp src/nostr.h
    src/ble.cpp src/ble.h
    src/bleframes.cpp src/bleframes.h
    src/tls.cpp src/tls.h
)

target_compile_definitions(huginn PRIVATE HUGINN_VERSION="${HUGINN_VERSION}")

set_source_files_properties(qml/Theme.qml qml/Ui.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

# Fonts are fetched by tools/get-fonts.sh (CI does it before every build).
file(GLOB HUGINN_FONTS RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} fonts/*.ttf)

qt_add_qml_module(huginn
    URI Huginn
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Theme.qml
        qml/Ui.qml
        qml/Icon.qml
        qml/PageHeader.qml
        qml/PromptKey.qml
        qml/PromptBar.qml
        qml/HButton.qml
        qml/ToggleSwitch.qml
        qml/Segmented.qml
        qml/TextPrompt.qml
        qml/ConfirmDialog.qml
        qml/Toast.qml
        qml/ListPicker.qml
        qml/Rail.qml
        qml/ChatPage.qml
        qml/FilesPage.qml
        qml/CallsPage.qml
        qml/SettingsPage.qml
        qml/ContactsPage.qml
        qml/LockPanel.qml
        qml/PatternPad.qml
    RESOURCES
        qml/assets/raven-gold.png
        qml/assets/raven_message.wav
        ${HUGINN_FONTS}
)

# Keep resource paths short: qml/Main.qml is served as Main.qml.
foreach(f IN ITEMS Main Theme Ui Icon PageHeader PromptKey PromptBar HButton ToggleSwitch Segmented
                   TextPrompt ConfirmDialog Toast ListPicker Rail ChatPage FilesPage CallsPage SettingsPage
                   ContactsPage LockPanel PatternPad)
    set_source_files_properties(qml/${f}.qml PROPERTIES QT_RESOURCE_ALIAS ${f}.qml)
endforeach()
set_source_files_properties(qml/assets/raven-gold.png PROPERTIES QT_RESOURCE_ALIAS assets/raven-gold.png)
set_source_files_properties(qml/assets/raven_message.wav PROPERTIES QT_RESOURCE_ALIAS assets/raven_message.wav)
foreach(font IN LISTS HUGINN_FONTS)
    get_filename_component(name ${font} NAME)
    set_source_files_properties(${font} PROPERTIES QT_RESOURCE_ALIAS fonts/${name})
endforeach()

target_link_libraries(huginn PRIVATE
    Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Quick Qt6::QuickControls2 Qt6::Network Qt6::DBus
    Qt6::WebSockets Qt6::Bluetooth Qt6::Multimedia
    KF6::Prison
    PkgConfig::SDL2
    OpenSSL::Crypto
)

include(GNUInstallDirs)
install(TARGETS huginn RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(FILES packaging/io.github.ravenor907.Huginn.desktop DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
install(FILES packaging/io.github.ravenor907.Huginn.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo)
install(FILES packaging/io.github.ravenor907.Huginn-256.png DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/256x256/apps RENAME io.github.ravenor907.Huginn.png)
install(FILES packaging/io.github.ravenor907.Huginn-512.png DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/512x512/apps RENAME io.github.ravenor907.Huginn.png)

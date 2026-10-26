cmake_minimum_required(VERSION 3.22)

# Two-part versions only (0.1, 0.2, 1.0). Test builds add " Test N".
set(HUGINN_VERSION "0.1" CACHE STRING "Version shown in the app")
project(huginn VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.6 REQUIRED COMPONENTS Core Gui Widgets Quick QuickControls2 Network DBus Concurrent)
find_package(KF6CoreAddons REQUIRED)
find_package(KF6KIO REQUIRED)
find_package(KF6Archive REQUIRED)
find_package(KF6Prison REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SDL2 REQUIRED IMPORTED_TARGET sdl2)

qt_standard_project_setup(REQUIRES 6.6)

qt_add_executable(huginn
    src/main.cpp
    src/settings.cpp src/settings.h
    src/system.cpp src/system.h
    src/gamepad.cpp src/gamepad.h
    src/foldermodel.cpp src/foldermodel.h
    src/fileops.cpp src/fileops.h
    src/places.cpp src/places.h
    src/steam.cpp src/steam.h
    src/archivemodel.cpp src/archivemodel.h
    src/transfer.cpp src/transfer.h
    src/network.cpp src/network.h
    src/usb.cpp src/usb.h
    src/updater.cpp src/updater.h
    src/storage.cpp src/storage.h
    src/localsend.cpp src/localsend.h
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
        qml/Divider.qml
        qml/PageHeader.qml
        qml/PromptKey.qml
        qml/PromptBar.qml
        qml/HButton.qml
        qml/ToggleSwitch.qml
        qml/Segmented.qml
        qml/FileRow.qml
        qml/FilePane.qml
        qml/ActionWheel.qml
        qml/TextPrompt.qml
        qml/ConfirmDialog.qml
        qml/Toast.qml
        qml/BrowserPage.qml
        qml/GamesPage.qml
        qml/UsbPage.qml
        qml/TransferPage.qml
        qml/NetworkPage.qml
        qml/SettingsPage.qml
        qml/PreviewPage.qml
        qml/ArchivePage.qml
        qml/StoragePage.qml
        qml/DestinationPicker.qml
        qml/HomePage.qml
        qml/RecentPage.qml
    RESOURCES
        qml/assets/raven-gold.png
        ${HUGINN_FONTS}
)

# Keep resource paths short: qml/Main.qml is served as Main.qml.
foreach(f IN ITEMS Main Theme Ui Icon Divider PageHeader PromptKey PromptBar HButton ToggleSwitch Segmented FileRow FilePane
                   ActionWheel TextPrompt ConfirmDialog Toast BrowserPage GamesPage UsbPage TransferPage NetworkPage
                   SettingsPage PreviewPage ArchivePage StoragePage DestinationPicker HomePage RecentPage)
    set_source_files_properties(qml/${f}.qml PROPERTIES QT_RESOURCE_ALIAS ${f}.qml)
endforeach()
set_source_files_properties(qml/assets/raven-gold.png PROPERTIES QT_RESOURCE_ALIAS assets/raven-gold.png)
foreach(font IN LISTS HUGINN_FONTS)
    get_filename_component(name ${font} NAME)
    set_source_files_properties(${font} PROPERTIES QT_RESOURCE_ALIAS fonts/${name})
endforeach()

target_link_libraries(huginn PRIVATE
    Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Quick Qt6::QuickControls2 Qt6::Network Qt6::DBus Qt6::Concurrent
    KF6::CoreAddons KF6::KIOCore KF6::KIOWidgets KF6::Archive KF6::Prison
    PkgConfig::SDL2
)

include(GNUInstallDirs)
install(TARGETS huginn RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(PROGRAMS helper/huginn-drivetool DESTINATION ${CMAKE_INSTALL_LIBEXECDIR})
install(FILES packaging/io.github.ravenor907.Huginn.desktop DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
install(FILES packaging/io.github.ravenor907.Huginn.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo)
install(FILES packaging/io.github.ravenor907.Huginn-256.png DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/256x256/apps RENAME io.github.ravenor907.Huginn.png)
install(FILES packaging/io.github.ravenor907.Huginn-512.png DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/512x512/apps RENAME io.github.ravenor907.Huginn.png)

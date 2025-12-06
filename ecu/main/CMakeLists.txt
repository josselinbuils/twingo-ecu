idf_component_register(
    SRCS "ble.c" "external/misc.c" "external/peer.c" "i2c.c" "lcd.c" "main.c" "ui.c" "ui/g_meter.c"  "ui/twingo_logo.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES bt nvs_flash)

idf_component_get_property(lvgl_lib lvgl__lvgl COMPONENT_LIB)
target_compile_options(${lvgl_lib} PRIVATE -Wno-format)

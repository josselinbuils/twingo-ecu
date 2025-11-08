idf_component_register(
    SRCS "ble.c" "external/misc.c" "external/peer.c" "i2c.c" "lcd.c" "main.c" "twingo_logo.c" "ui.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES bt nvs_flash)

idf_component_get_property(lvgl_lib lvgl__lvgl COMPONENT_LIB)
target_compile_options(${lvgl_lib} PRIVATE -Wno-format)

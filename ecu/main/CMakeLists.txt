idf_component_register(
    SRCS "external/waveshare_twai_port.c" "lcd.c" "main.c" "twingo_logo.c"
    INCLUDE_DIRS ".")

idf_component_get_property(lvgl_lib lvgl__lvgl COMPONENT_LIB)
target_compile_options(${lvgl_lib} PRIVATE -Wno-format)

cd [file dirname [info script]]
open_project gv_host.gprj
set_option -top_module top_gv_host
set_option -bit_security 1
set_option -use_i2c_as_gpio 1
set_option -use_cpu_as_gpio 1
set_option -use_sspi_as_gpio 1
set_option -place_option 1
set_option -route_option 0
set_option -resource_sharing 1
run all


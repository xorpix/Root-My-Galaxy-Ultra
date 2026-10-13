cmake_minimum_required(VERSION 3.22.1)
project(rmgu_bootprobe C)
add_library(bootprobe SHARED bootprobe.c)
target_compile_options(bootprobe PRIVATE -Wall -Wextra -Werror)

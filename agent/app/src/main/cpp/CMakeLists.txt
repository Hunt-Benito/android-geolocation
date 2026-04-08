cmake_minimum_required(VERSION 3.22.1)

project("agent")

add_library(
    agent
    SHARED
    native_crypto.c
    native_stealth.c
)

find_library(crypto-lib crypto)
find_library(log-lib log)

target_link_libraries(
    agent
    ${crypto-lib}
    ${log-lib}
)

target_compile_options(agent PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)

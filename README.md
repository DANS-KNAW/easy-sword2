easy-sword2
===========
[![Build Status](https://travis-ci.org/DANS-KNAW/easy-sword2.png?branch=master)](https://travis-ci.org/DANS-KNAW/easy-sword2)

SYNOPSIS
--------

    easy-sword2 run-service


DESCRIPTION
-----------

EASY SWORD v2 Deposit Service


ARGUMENTS
---------

    Options:

        --help      Show help message
        --version   Show version of this program

    Subcommand: run-service - Starts EASY SWORD v2 as a daemon that services HTTP requests
        --help   Show help message
    ---

INSTALLATION AND CONFIGURATION
------------------------------


1. Unzip the tarball to a directory of your choice, typically `/usr/local/`
2. A new directory called easy-sword2-<version> will be created
3. Add the command script to your `PATH` environment variable by creating a symbolic link to it from a directory that is
   on the path, e.g. 
   
        ln -s /usr/local/easy-sword2-<version>/bin/easy-sword2 /usr/bin



General configuration settings can be set in `cfg/application.properties` and logging can be configured
in `cfg/logback.xml`. The available settings are explained in comments in aforementioned files.


BUILDING FROM SOURCE
--------------------

Prerequisites:

* Java 8 or higher
* Maven 3.3.3 or higher

Steps:

        git clone https://github.com/DANS-KNAW/easy-sword2.git
        cd easy-sword2
        mvn install

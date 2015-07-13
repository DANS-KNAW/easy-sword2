easy-deposit
============

Receive EASY-bags over a SWORD v2.0 session


SYNOPSIS
--------

    easy-deposit [-g]


DESCRIPTION
-----------






ARGUMENTS
---------

* -g, --git-repo-enabled: enables the creation of a git-repository per deposit. 


EXAMPLES
--------

The following are example sessions using [cURL] as a client.

### Single Deposit

If a deposit is not too large it can be transferred in one http session

    curl -v -H "Content-Type: application/zip" \
        -H "Content-Disposition: attachment; filename=example-bag.zip" \
        -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" \ 
        -H "Content-MD5: 2d48ff55b2c745345db1a86694068b84" \ 
        -i -u USER:PASSWORD --data-binary @example-bag.zip http://localhost:8080/collection


### Continued Deposit

If a deposit is too large to be transferred in one go, or it is not practical to do so for some other reason it can 
be sent in several increments. The status of the deposit is specified in the ``In-Progress`` header.

#### First Transfer 

(notice "`In-Progress: true`" header):

    curl -v -H "Content-Type: application/zip" \
            -H "Content-Disposition: attachment; filename=part1.zip" \
            -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" \
            -H "Content-MD5: ce17fca299eab53a9622fdf40b7450c1" \
            -H "In-Progress: true" \
            -i -u USER:PASSWORD --data-binary @part1.zip http://localhost:8080/collection


#### Intermediate Transfers 

(notice URI contains the ID which can be found in the response of the first transfer):

    curl -v -H "Content-Type: application/zip" \
            -H "Content-Disposition: attachment; filename=part2.zip" \
            -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" \ 
            -H "Content-MD5: 67c8773a4dfff6d93e12002868c5395d" \
            -H "In-Progress: true" \ 
            -i -u USER:PASSWORD --data-binary @part2.zip http://localhost:8080/collection/1435188185031

#### Final Transfer (

notice "`In-Progress: false`" header)

    curl -v -H "Content-Type: application/zip" \ 
            -H "Content-Disposition: attachment; filename=part3.zip" \
            -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" \
            -H "Content-MD5: 6c55ed00d3eadae513e720eb0f0489be" \
            -H "In-Progress: false" \ 
            -i -u USER:PASSWORD --data-binary @part3.zip http://localhost:8080/collection/1435188185031

### Check Existence of Deposit

To check if the deposit still exists you can simple do a ``GET`` request on its URL:

    curl -v -u USER:PASSWORD https://localhost:8080/collection/1435188185031

Response code is `200` if dataset exists, otherwise `404`.


INSTALLATION AND CONFIGURATION
------------------------------

### Installation steps:

1. Unzip the tarball to a directory of your choice, e.g. /opt/
2. A new directory called easy-deposit-<version> will be created
3. Create an environment variabele ``EASY_DEPOSIT_HOME`` with the directory from step 2 as its value
4. Either deploy the file ``$EASY_DEPOSIT_HOME/bin/easy-deposit.war`` in the Tomcat ``webapps`` directory or use the 
   deployment descriptor ``$EASY_DEPOSIT_HOME/bin/easy-deposit.xml`` and put it in ``/etc/tomcat6/Catalina/localhost``.

### Configuration

General configuration settings can be set in ``$EASY_DEPOSIT_HOME/cfg/application.properties`` and logging can be
configured in ``$EASY_DEPOSIT_HOME/cfg/logback.xml``. The available settings are explained in comments in 
aforementioned files.


BUILDING FROM SOURCE
--------------------

Prerequisites:

* Java 7 or higher
* Maven 3.3.3 or higher
 
Steps:

1. Clone and build the [dans-parent] project (*can be skipped if you have access to the DANS maven repository*)
      
        git clone https://github.com/DANS-KNAW/dans-parent.git
        cd dans-parent
        mvn install
2. Clone and build this project

        git clone https://github.com/DANS-KNAW/easy-deposit.git
        cd easy-deposit
        mvn install

[SWORDv2]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html
[BagIt]: https://tools.ietf.org/html/draft-kunze-bagit-10
[cURL]: https://en.wikipedia.org/wiki/CURL

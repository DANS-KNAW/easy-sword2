easy-deposit
============

Receive EASY-bags over a SWORD v2 session


DESCRIPTION
-----------

Service that receives [EASY-BagIt] packages and stores them on disk. The protocol used is [SWORD v2]. The client has the
option of sending the package in one http session (simple deposit) or several (continued deposit). A continued deposit 
is in progress as long as the client keeps adding the ``In-Progress: true`` http header. Authentication is done either
through a username/password pair configured in the `application.properties` file (single user setup) or through an LDAP 
directory (multi-user setup). After a deposit-transfer is completed it is made available for subsequent (human and/or 
machine) processing at the path `<deposits-root-dir>/[<user>/]<deposit-ID>`, in which:

  * `<deposits-root-dir>` is the configured directory on the server under which deposits are to be stored;
  * `<user>` is user ID of the user (only in multi-user setup);
  * `<deposit-ID>` is a unique ID for the deposit.
  
Optionally, the deposit directory is initialized with a git-repository. This is useful if it is to be used for curation
purposes. The transformations performed to the deposit can then be controlled and recorded in git. `easy-deposit` also
uses git to report to the client about the current state of the deposit after it has been finalized.

### Authentication

`easy-deposit` assumes that communications are done through a secure http connection. Therefore basic authentication 
is used.

  
### Simple Deposit
  
In a simple deposit the whole deposit package is sent in one http session. It is done by [posting to the collection IRI] with 
with following headers:

 Header                  |  Content                               
-------------------------|-------------------------------------------------------------------------------------
 `Content-Type`          | `application/zip`                       
 `Content-Disposition`   | `attachment; filename: package.zip` ("`package.zip`" can be something else, it is ignored)
 `Packaging`             | `http://easy.dans.knaw.nl/schemas/EASY-BagIt.html` 
 `Content-MD5`           | the MD5 digest of the payload encoded as hexadecimal number in ascii
  
  
### Continued Deposit
  
In a continued deposit the deposit package is divided up into partial deposits. Then each partial deposit is sent 
with the following headers:

 Header                  |  Content                               
-------------------------|-------------------------------------------------------------------------------------
 `Content-Type`          | `application/zip` or `application/octet-stream` (see below)                    
 `Content-Disposition`   | `attachment; filename: package.zip[.<seq nr>]` (for `<seq nr>` see below)
 `Packaging`             | `http://easy.dans.knaw.nl/schemas/EASY-BagIt.html` 
 `Content-MD5`           | the MD5 digest of the payload encoded as hexadecimal number in ascii
 `In-Progress`           | `true` for all partial deposits, except the last, where it must be `false`

Authentication

The correct order of the parts is communicated by the client by extending the filename with a sequence number. The
parts may be sent in any order, provided that the client sends the last part after it receives the 
deposit receipt for all the other parts. The reason for this is that the last partial deposit triggers the server
to attempt to assemble the complete deposit from the parts. It must therefore be able to rely on all the partial
deposits being uploaded to the server. 

The clients has two options for dividing up a deposit in partial deposits:

  * make every partial deposit a valid zip file, containing some of the bag's files. In this case, upon receiving the
    final deposit, `easy-deposit` will unzip all the partial deposits to a single directory to create the resulting bag.
    The client must therefore take care not to "overwrite" files from a previous partial deposit, as this will lead to
    undefined behavior. The client selects this option by using the `Content-Type: application/zip` header.
  * create a single zip file, split it up into chunks and send each chunk as a partial deposit. In this case, upon receiving
    the final deposit, `easy-deposit` will concatenate all the partial deposits to recreate the zip file and unzip this
    file to create the resulting bag. The client must specify the intended order of the parts by extending the file name
    the Content-Diposition header with a dot and a sequence number, e.g., 
    `Content-Disposition: attachment; filename=example-bag.zip.part.3` for the third partial deposit. The client selects
    this option by using the `Content-Type: application/octet-stream` header (to indicate that the partial deposit
    on its own is not a valid zip archive).

### States

A deposit goes through several states. A continued deposit that is still open for additions is said to be in `DRAFT`
state. Once the client closed the deposit by sending `In-Progress: false` it enters `FINALIZING` state (a simple 
deposit goes straigth to the state). During this state `easy-deposit` assembles the deposit and validates it. If the
the deposit is valid it transitions to the `SUBMITTED` state, otherwise it is flagged as `INVALID`. 

If git-support is enabled any number of additional states may be defined. `easy-deposit` will always use the state 
recorded in the latest tag of the form `state=<state-label>`. We recommend the states `ARCHIVED` and `REJECTED` to
indicate that subsequent processing of the deposit was successful or not.

`POST`-ing to a deposit is only allowed when it is in `DRAFT` state. In all other states this will lead to
[method not allowed error].

When state is set to `ARCHIVED` the working directory is cleared and committed to save space. Also if commit message
of the `ARCHIVED` tag contains a URL it is reported to clients as the archiving URL of the resulting dataset.

After closing its deposit the client must retrieve the state of the deposit by getting its [SWORD statement]. Only
after establishing that the state has gone to `SUBMITTED` may the client assume that the deposit has been received
correctly. After that it may still be necessary for the client to monitor the deposit's state to change to `ARCHIVED`
in order to retrieve the permanent URI for the deposited files.

| State               | Description                                                                                  |
| :-----------------  | :------------------------------------------------------------------------------------------- |
| `DRAFT`             | Continued deposit in progress                                                                |
| `FINALIZING`        | Deposit has been closed by the client, service is creating and validating the deposit        | 
| `INVALID`           | Deposit was finalized but turned out to be invalid (i.e. not a valid EASY bag)               |
| `SUBMITTED`         | Deposit was finalized and was a valid bag, and is being processed                            | 
| `REJECTED`          | Deposit was finalized, a valid bag, but was rejected for some other reason                   | 
| `ARCHIVED`          | Deposit was successfully archived. (Access URL in commit message.)                           | 
[States]


EXAMPLES
--------

The following are example sessions using [cURL] as a client. `easy-deposit` is assumed to be running on `localhost` at port 
8080 and to be configured with a user with username  `USER` and password `PASSWORD`. The example data sent can be found in
the examples in this project.

### Single Deposit

If a deposit is not too large it can be transferred in one http session:

    curl -v -H "Content-Type: application/zip" \
        -H "Content-Disposition: attachment; filename=example-bag.zip" \
        -H "Packaging: http://easy.dans.knaw.nl/schemas/EASY-BagIt.html" \ 
        -H "Content-MD5: 2d48ff55b2c745345db1a86694068b84" \ 
        -i -u USER:PASSWORD \
        --data-binary @example-bag.zip http://localhost:8080/collection


### Continued Deposit

If a deposit is too large to be transferred in one go it can be sent in several increments. Whether any more partial 
deposits are to be expected is indicated by the client in the ``In-Progress`` header.

#### First Transfer 

(notice "`In-Progress: true`" header):

    curl -v -H "Content-Type: application/zip" \
            -H "Content-Disposition: attachment; filename=part1.zip" \
            -H "Packaging: http://easy.dans.knaw.nl/schemas/EASY-BagIt.html" \
            -H "Content-MD5: ce17fca299eab53a9622fdf40b7450c1" \
            -H "In-Progress: true" \
            -i -u USER:PASSWORD --data-binary @part1.zip http://localhost:8080/collection


#### Intermediate Transfers 

(notice URI contains the ID which can be found in the response of the first transfer):

    curl -v -H "Content-Type: application/zip" \
            -H "Content-Disposition: attachment; filename=part2.zip" \
            -H "Packaging: http://easy.dans.knaw.nl/schemas/EASY-BagIt.html" \ 
            -H "Content-MD5: 67c8773a4dfff6d93e12002868c5395d" \
            -H "In-Progress: true" \ 
            -i -u USER:PASSWORD \
            --data-binary @part2.zip http://localhost:8080/collection/1435188185031

#### Final Transfer 

(notice "`In-Progress: false`" header)

    curl -v -H "Content-Type: application/zip" \ 
            -H "Content-Disposition: attachment; filename=part3.zip" \
            -H "Packaging: http://easy.dans.knaw.nl/schemas/EASY-BagIt.html" \
            -H "Content-MD5: 6c55ed00d3eadae513e720eb0f0489be" \
            -H "In-Progress: false" \ 
            -i -u USER:PASSWORD \ 
            --data-binary @part3.zip http://localhost:8080/collection/1435188185031

### Check Existence of Deposit

To check if the deposit still exists you can simple do a ``GET`` request on its URL:

    curl -v -u USER:PASSWORD https://localhost:8080/collection/1435188185031

Response code is `200` if dataset exists, otherwise `404`.


INSTALLATION AND CONFIGURATION
------------------------------

### Installation steps:

1. Unzip the tarball to a directory of your choice, e.g. `/opt/
2. A new directory called `easy-deposit-<version> will be created`. This is the service's home directory.
3. Configure the service's home directory in one of two ways:
    * Set the init param `EASY_DEPOSIT_HOME` to point to the home directory. (In Tomcat this can be done by 
      embedding a `Parameter` element in the [context descriptor].)
    * Set the enviroment variable `EASY_DEPOSIT_HOME` to point to the home directory.
4. Either deploy the file ``$EASY_DEPOSIT_HOME/bin/easy-deposit.war`` in the Tomcat ``webapps`` directory or use the 
   context descriptor ``$EASY_DEPOSIT_HOME/bin/easy-deposit.xml`` and put it in ``/etc/tomcat6/Catalina/localhost``.

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

[EASY-BagIt]: http://easy.dans.knaw.nl/schemas/EASY-BagIt.html
[SWORD v2]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html
[SWORD v2 - Continued Deposit]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#continueddeposit
[method not allowed error]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#errordocuments_uris_notallowed
[BagIt]: https://tools.ietf.org/html/draft-kunze-bagit-11
[cURL]: https://en.wikipedia.org/wiki/CURL
[git]: http://www.git-scm.com/
[dans-parent]: https://github.com/DANS-KNAW/dans-parent
[posting to the collection IRI]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#protocoloperations_creatingresource
[SWORD statement]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#statement
[context descriptor]: https://tomcat.apache.org/tomcat-6.0-doc/config/context.html



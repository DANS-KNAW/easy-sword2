easy-deposit
============

Receive EASY-bags over a SWORD v2 session


SYNOPSIS
--------

    easy-deposit [-g]


DESCRIPTION
-----------

Service that receives [EASY-BagIt] packages and stores them on disk. The protocol used is [SWORD v2]. The client has the
option of sending the package in one http session or several. The latter case is called a "continued deposit". A 
continued deposit is in progress as long as the client keeps adding the ``In-Progress: true`` header (see also: 
[SWORD v2 - Continued Deposit]). The client must not send this last partial deposit before it receives a response for
all the other partial deposits.

After finalizing the deposit with ``In-Progress: false`` the service will merge the received parts and check the resulting
bag for validity (see [BagIt] for a definition of validity). 

The clients has two options for dividing up a deposit in partial deposit:
* make every partial deposit a valid zip file, containing some of the bag's files. In this case, upon receiving the
  final deposit, `easy-deposit` will unzip all the partial deposits to a single directory to create the resulting bag.
  The client must therefore take care not to "overwrite" files from a previous partial deposit, as this will lead to
  undefined behavior. When using this option the client must use the `Content-Type: application/zip` header.
* create a single zip file, split is in chunks and send each chunk as a partial deposit. In this case, upon receiving
  the final deposit, `easy-deposit` will concatenate all the partial deposits to recreate the zip file and upzip this
  file to create the resulting bag. The client must specify the intended order of the parts by extending the file name
  the Content-Diposition header with a dot and a sequence number, e.g., 
  `Content-Disposition: attachment; filename=example-bag.zip.part.3` for the third partial deposit. When using this
  option the client must use the `Content-Type: application/octet-stream` header, to indicate that the partial deposit
  on its own is not a valid zip-archive.

If `--git-enabled` is specified the service will initialize a [git]-repository in the resulting bag-directory and create
an initial commit. `easy-deposit` will use the tags in this git-repository to report the current state of the deposit
to clients. Tags that indicate a state must have a label of the form

        state=<state-name>
        
where `<state-name>` is one of:

* `DRAFT`
* `FINALIZING`
* `INVALID`
* `SUBMITTED`
* `ARCHIVED`

When state is set to `ARCHIVED` the working directory is cleared and committed to save space. Also if commit message
of the `ARCHIVED` tag contains a URL it is reported to clients as the archiving URL of the resulting dataset.

*TODO: decide on the complete set of states and their semantics*

ARGUMENTS
---------

* ``-g``, ``--git-enabled`` -- each deposit will be stored in a [git] repository.


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

[EASY-BagIt]: http://easy.dans.knaw.nl/schemas/EASY-BagIt.html
[SWORD v2]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html
[SWORD v2 - Continued Deposit]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#continueddeposit
[BagIt]: https://tools.ietf.org/html/draft-kunze-bagit-11
[cURL]: https://en.wikipedia.org/wiki/CURL
[git]: http://www.git-scm.com/
[dans-parent]: https://github.com/DANS-KNAW/dans-parent

Directory Services Portal @VERSION@: license file

Without an ENS Solutions license the portal shows only the sign-in page and, to administrators,
the license screen. Get the license file (.lic) from your account on the ENS licensing portal;
for a trial, download an evaluation license there.

Either upload it on the license screen (sign in as an administrator: Administration > License),
or put it here, named license.lic, before running ./install.sh (or ./upgrade.sh):

    cp /path/to/your-license.lic license/license.lic

The installer then sets ENS_LICENSE in .env from it, and the API installs it as it starts: when
no usable license is installed yet, or when it is a newer revision of the installed one. To
install a license from the command line at any time:

    ./portal.sh license /path/to/your-license.lic

Without the shared database this also writes it into the license volume (ens-license, mounted at
/var/lib/ens/directory-portal), replacing an installed license. With the database
(DSP_DATABASE_URL) the license is kept there and the API is restarted to install it; a different
license already installed is kept, so upload that one on the license screen instead.

The license screen also shows this installation's id, which you need to bind a license to this
installation on the ENS licensing portal. The id is kept with the license: keep the volume (and,
with the shared database, the database).

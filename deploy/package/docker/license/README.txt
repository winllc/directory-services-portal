Directory Services Portal @VERSION@: license file

Without an ENS Solutions license the portal shows only the sign-in page and, to administrators,
the license screen. Get the license file (.lic) from your account on the ENS licensing portal;
for a trial, download an evaluation license there.

Either upload it on the license screen (sign in as an administrator: Administration > License),
or put it here, named license.lic, before running ./install.sh (or ./upgrade.sh):

    cp /path/to/your-license.lic license/license.lic

The installer then copies it into the api container's license volume (ens-license, mounted at
/var/lib/ens/directory-portal), if that volume has no license yet. To replace an installed license
from the command line:

    ./portal.sh license /path/to/your-license.lic

The license screen also shows this installation's id, which you need to bind a license to this
installation on the ENS licensing portal. The id is kept in the same volume: keep the volume.

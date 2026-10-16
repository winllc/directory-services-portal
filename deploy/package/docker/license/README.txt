Directory Services Portal @VERSION@: license directory

Put your ENS Solutions license file here, named license.lic:

    cp /path/to/your-license.lic license/license.lic
    chmod 644 license/license.lic

This directory is mounted read-only into the API container at /etc/ens/license. The license
enables data analysis, metrics and schema import/export; without it the portal runs its core
features. A renewed file is picked up within a minute, no restart needed.

package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;

public class MappingNotFoundException extends TranslationException {

    public MappingNotFoundException(ContextualTermCode ctc) {
        super("No mapping found for: " + ctc.context().code() + "/" + ctc.termCode().system() + "|" + ctc.termCode().code());
    }
}

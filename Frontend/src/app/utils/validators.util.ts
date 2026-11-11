import {AbstractControl, ValidationErrors, ValidatorFn} from "@angular/forms";

/**
 * Rejects a value that is non-empty but blank after trimming (e.g. a single space). Meant to be
 * combined with `Validators.required`, which alone treats whitespace-only input as valid - without
 * this, a stray-space name passes client-side validation and only fails once the backend rejects
 * it (via its own isBlank() check) after the dialog has already closed.
 */
export function notBlank(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
        const value = control.value;
        if (typeof value === "string" && value.length > 0 && value.trim().length === 0) {
            return {blank: true};
        }
        return null;
    };
}

/** Like the backend: tenant users' and admins' passwords need at least this many characters. */
export const PASSWORD_MIN_LENGTH = 12;

const USERNAME_PATTERN = /^[A-Za-z0-9._@-]{3,100}$/;

/** A login name as the backend accepts it: 3-100 letters, digits or . _ - @ (surrounding blanks are trimmed). Empty is left to `required`. */
export function username(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
        const value = typeof control.value === "string" ? control.value.trim() : "";
        if (value.length === 0) {
            return null;
        }
        return USERNAME_PATTERN.test(value) ? null : {username: true};
    };
}

/** On a form group: the field `repeatKey` must equal `passwordKey`; an empty repeat is left to `required`. */
export function passwordsMatch(passwordKey: string, repeatKey: string): ValidatorFn {
    return (group: AbstractControl): ValidationErrors | null => {
        const repeat = group.get(repeatKey)?.value as string | null | undefined;
        if (!repeat) {
            return null;
        }
        return group.get(passwordKey)?.value === repeat ? null : {passwordMismatch: true};
    };
}

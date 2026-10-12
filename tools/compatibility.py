"""Run with the Windows KeyShelf project .venv. Uses synthetic data only."""
import sys
from pathlib import Path

WINDOWS = Path(__file__).resolve().parents[2] / "KeyShelf"
sys.path.insert(0, str(WINDOWS))

from app.core.models import Account, Field  # noqa: E402
from app.services.vault import Vault  # noqa: E402

OUTPUT = Path(__file__).resolve().parents[2] / "temp-dev" / "KeyShelf-Android" / "compatibility"
PASSWORD = "synthetic-master-123"


def generate():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    alias_path = OUTPUT / "windows-alias.kdbx"
    if not alias_path.exists():
        vault, _ = Vault.create(alias_path, PASSWORD)
        service = vault.service("Google").services[0]
        vault.account(Account(service_id=service.id, title="Личный аккаунт", alias=".p", kind="account",
            fields=[Field("Пароль", "synthetic-alias-password"), Field("Секрет 2FA", "")]))
        vault.close()
    path = OUTPUT / "windows.kdbx"
    if path.exists():
        print("Synthetic fixture already exists; preserving it.")
        return
    vault, _ = Vault.create(path, PASSWORD)
    state = vault.service("Почта")
    vault.account(Account(service_id=state.services[0].id, title="Почта рабочая", login="work-login",
        notes="synthetic private note", favorite=True, kind="account", fields=[
            Field("Пароль", "synthetic-password"), Field("Секрет 2FA", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
            Field("Дополнительное поле 1", "synthetic-extra")],
        field_layout=["Логин", "Пароль", "Секрет 2FA", "Дополнительное поле 1"]))
    vault.account(Account(service_id=state.services[0].id, title="API example", kind="api", api_paid=True,
        fields=[Field("API-ключ", "synthetic-api")], field_layout=["API-ключ"]))
    vault.close()
    print("Synthetic Windows KDBX fixture generated.")


def verify():
    vault, state = Vault.open(OUTPUT / "android-alias.kdbx", PASSWORD)
    assert state.accounts[0].title == "Личный аккаунт"
    assert state.accounts[0].alias == ".phone"
    vault.account(state.accounts[0])
    vault.close()
    vault, state = Vault.open(OUTPUT / "android-alias.kdbx", PASSWORD)
    assert state.accounts[0].alias == ".phone"
    vault.close()
    vault, state = Vault.open(OUTPUT / "android-edited.kdbx", PASSWORD)
    account = next(a for a in state.accounts if a.kind == "account")
    assert account.title == "Почта с телефона"
    assert account.alias == ".p"
    assert account.favorite
    assert next(f.value for f in account.fields if f.label == "Пароль") == "synthetic-mobile-password"
    assert account.fields[-1].value == "synthetic-extra"
    assert account.field_layout == ["Логин", "Пароль", "Секрет 2FA", "Дополнительное поле 1"]
    assert next(a for a in state.accounts if a.kind == "api").api_paid
    vault.close()
    vault, state = Vault.open(OUTPUT / "android-created.kdbx", PASSWORD)
    account = state.accounts[0]
    assert account.title == "Мобильный аккаунт" and account.login == "phone-login"
    assert account.kind == "account"
    vault.close()
    print("Android-edited and Android-created KDBX verified in Windows KeyShelf.")
    vault, state = Vault.open(OUTPUT / "android-deleted-fields.kdbx", PASSWORD)
    account = next(a for a in state.accounts if a.kind == "account")
    assert {f.label for f in account.fields} == {"Пароль", "Секрет 2FA", "Дополнительное поле 1"}
    assert account.field_layout == ["Логин", "Дополнительное поле 1"]
    vault.close()
    print("Deleted fields and renumbered extras verified in Windows KeyShelf.")


if __name__ == "__main__":
    (verify if "--verify" in sys.argv else generate)()

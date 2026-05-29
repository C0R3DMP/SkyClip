import json
import logging
import requests
import ctypes
from core.constants import *
from core.config import Config
from bs4 import BeautifulSoup
from utils.ssl_helper import requests_verify_arg


def _secure_clear(data: bytes) -> None:
    """Overwrite bytes in memory before deletion (prevent memory dump recovery)."""
    if data:
        ctypes.memset(id(data), 0, len(data))


class RequestManager:
    def __init__(self, config: Config):
        self.config = config

    def _verify(self):
        return requests_verify_arg(self.config)

    @staticmethod
    def format_cookie(cookie: dict) -> str:
        """
        Format the cookie string for headers.
        """
        return f"JSESSIONID={cookie.get('JSESSIONID', '')};"

    def login(self) -> tuple[bool, str, dict]:
        try:
            session = requests.Session()

            # Fetch the login page to get the CSRF token
            response = session.get(
                self.config.data["server_url"] + LOGIN_URL,
                verify=self._verify(),
            )

            if response.status_code != 200:
                msg = f"Failed to fetch login page: {response.status_code}"
                logging.error(msg)
                return False, msg, None

            soup = BeautifulSoup(response.text, "html.parser")
            csrf_token = soup.find("input", {"name": "_csrf"})["value"]

            # Login with the credentials
            form_data = {
                "username": self.config.data["username"],
                "password": self.config.data["password"],
                "_csrf": csrf_token,
            }
            response = session.post(
                self.config.data["server_url"] + LOGIN_URL,
                data=form_data,
                verify=self._verify(),
            )
            if (
                response.status_code == 200
                and "bad credentials" not in response.text.lower()
            ):
                # login successful
                cookie = session.cookies.get_dict()
                logging.info(f"Login successful: {response.status_code}")
                return True, "Login successful", cookie
            else:
                # login failed
                msg = f"Login failed: {response.status_code}"
                logging.error(msg)
                return False, msg, None
        except Exception as e:
            msg = f"An error occurred during login: {e}"
            logging.error(msg)
            return False, msg, None

    def maxsize(self) -> int:
        try:
            response = RequestManager.get(
                url=self.config.data["server_url"] + MAXSIZE_URL,
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"])
                },
                verify=self._verify(),
            )
            if response.status_code == 200:
                # maxsize request successful
                maxsize = response.json().get("maxsize", MAX_SIZE)
                logging.info(f"Max size: {maxsize}")
                return maxsize
        except Exception as e:
            logging.error(
                f"Error fetching max size: {e}, defaulting to {MAX_SIZE} Bytes"
            )
        return MAX_SIZE

    def get_server_mode(self) -> str:
        try:
            response = RequestManager.get(
                url=self.config.data["server_url"] + SERVER_MODE_URL,
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"])
                },
                verify=self._verify(),
            )
            if response.status_code == 200:
                # server mode request successful
                server_mode = response.json().get("mode")
                logging.info(f"Server mode: {server_mode}")
                return server_mode
        except Exception as e:
            logging.error(f"Error fetching server mode: {e}")
            raise

    def get_stun_url(self) -> str:
        try:
            response = RequestManager.get(
                url=self.config.data["server_url"] + STUN_URL,
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"])
                },
                verify=self._verify(),
            )
            if response.status_code == 200:
                # stun url request successful
                stun_url = response.json().get("url")
                logging.info(f"STUN URL: {stun_url}")
                return stun_url
        except Exception as e:
            logging.error(f"Error fetching STUN URL: {e}")
            raise

    def get_metadata(self) -> dict:
        try:
            response = RequestManager.get(
                url=METADATA_URL,
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"])
                },
                verify=True,
            )
            if response.status_code == 200:
                # metadata request successful
                return response.json()
        except Exception as e:
            logging.error(f"Error fetching metadata: {e}")
            raise

    def logout(self):
        try:
            response = RequestManager.post(
                url=self.config.data["server_url"] + LOGOUT_URL,
                data={"_csrf": self.config.data["csrf_token"]},
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"])
                },
                verify=self._verify(),
            )
            if response.status_code == 204:
                logging.info(f"Logout successful: {response.status_code}")
        except Exception as e:
            logging.error(f"Error during logout: {e}")

    def get_csrf_token(self) -> str:
        try:
            response = RequestManager.get(
                url=self.config.data["server_url"] + CSRF_URL,
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"])
                },
                verify=self._verify(),
            )

            if response.status_code == 200:
                # CSRF token request successful
                return json.loads(response.text).get("token", "")
        except Exception as e:
            logging.error(f"Error fetching CSRF token: {e}")
            return ""

    def perform_ecdh_handshake(self) -> bytes:
        """
        Perform ECDH key exchange with server after successful login.

        Returns:
            session_key: 32-byte encryption key for in-transit communication

        Raises:
            RuntimeError: If handshake fails (no fallback)
        """
        try:
            from utils.ecdh_key_exchange import ECDHKeyExchange

            # Step 1: Generate ephemeral keypair
            client_private_pem, client_public_pem = ECDHKeyExchange.generate_keypair()
            logging.info("ECDH: Generated client keypair")

            # Step 2: Send client public key to server, receive server public key
            response = RequestManager.post(
                url=self.config.data["server_url"] + "/api/ecdh/handshake",
                json_data={"public_key": client_public_pem.decode("utf-8")},
                headers={
                    "Cookie": RequestManager.format_cookie(self.config.data["cookie"]),
                },
                verify=self._verify(),
            )

            if response.status_code != 200:
                raise RuntimeError(
                    f"ECDH handshake failed: {response.status_code} {response.text}"
                )

            server_public_pem = response.json()["public_key"].encode("utf-8")
            logging.info("ECDH: Received server public key")

            # Step 3: Perform key agreement and derive session key
            session_key = ECDHKeyExchange.perform_key_exchange(
                client_private_pem, server_public_pem
            )
            logging.info("ECDH: Session key derived")

            # Step 4: Secure memory clearing for ephemeral private key
            _secure_clear(client_private_pem)
            del client_private_pem, client_public_pem

            return session_key
        except Exception as e:
            raise RuntimeError(f"ECDH handshake failed: {e}")

    @staticmethod
    def get(url: str, headers: dict = None, verify=True) -> requests.Response:
        """
        A generic GET mapper for handling GET requests.
        """
        try:
            response = requests.get(url, headers=headers, verify=verify)
            response.raise_for_status()  # Will raise an HTTPError if the HTTP request returned an unsuccessful status code
            return response
        except Exception as e:
            logging.error(f"Error during GET request to {url}: {e}")
            raise

    @staticmethod
    def post(
        url: str, data=None, headers: dict = None, verify=True, json_data: dict = None
    ) -> requests.Response:
        """
        A generic POST mapper for handling POST requests.
        Supports both form data (data) and JSON (json_data).
        """
        try:
            if json_data is not None:
                response = requests.post(url, json=json_data, headers=headers, verify=verify)
            else:
                response = requests.post(url, data=data, headers=headers, verify=verify)
            response.raise_for_status()
            return response
        except Exception as e:
            logging.error(f"Error during POST request to {url}: {e}")
            raise
